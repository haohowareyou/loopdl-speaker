/*
 * loopkeyd — native button daemon for loop-speaker-mode (Android arm64 / MT6877).
 *
 * Grabs the physical button input devices with EVIOCGRAB, runs a gesture state
 * machine keyed by KEYCODE (not by source device), and re-injects single volume
 * taps through a uinput virtual device so normal volume control stays instant.
 * Recognised gestures are emitted as actions by invoking the shell dispatcher:
 *     sh /data/adb/loop-speaker-mode/scripts/loop-act <action>
 *
 * Device topology on this LoopDL (confirmed via getevent -lp, 2026-06-09):
 *   mtk-pmic-keys  -> KEY_VOLUMEUP (115) AND KEY_POWER (116)
 *   mtk-kpd        -> KEY_VOLUMEDOWN (114) ONLY
 *   mtk-tpd        -> touchscreen (BTN_TOUCH ...) — MUST NOT be grabbed
 *   ...Headset Jack -> headset-remote keys           — MUST NOT be grabbed
 *
 * The two physical-button devices are grabbed (names from config, defaults
 * "mtk-kpd" and "mtk-pmic-keys"). Because VOLUMEUP and VOLUMEDOWN live on
 * DIFFERENT devices, the state machine maintains a single GLOBAL pressed-key
 * set keyed by code (114/115/116) across every grabbed fd, so combos like
 * "both volumes held" work even when the events arrive on different fds.
 *
 * Gestures (thresholds read from config, defaults below):
 *   power single tap            -> "play_pause"  (fires INSTANTLY on release)
 *   power hold alone (no vol)    -> "shutdown"    (POWER_SHUTDOWN_MS; chime then power off)
 *   vol+ single tap             -> volume up      (re-injected after VOL_DOUBLETAP_MS)
 *   vol- single tap             -> volume down
 *   vol+ double tap             -> "next"   (two taps of vol-up  within VOL_DOUBLETAP_MS)
 *   vol- double tap             -> "prev"   (two taps of vol-down within VOL_DOUBLETAP_MS)
 *   vol+/vol- hold              -> ramp volume (after VOL_RAMP_DELAY_MS)
 *   both volumes held            -> "pair_open"   (GESTURE_PAIR_HOLD_MS)
 *   power + vol-down held        -> "mode_toggle" (GESTURE_MODE_HOLD_MS)  [-> full mode]
 *
 * Power play/pause is instant (no multi-tap), so the most common action has no latency.
 * Track skip/rewind lives on a volume DOUBLE-tap (up=next, down=prev): a single volume
 * tap is therefore deferred VOL_DOUBLETAP_MS to see whether a second tap follows, so a
 * lone volume tap carries that much latency (the cost of double-tap disambiguation; big
 * volume changes use hold-to-ramp instead of repeated taps). There is NO firmware power
 * menu in dumb mode — a power hold shuts the speaker down. Combos (pair/mode) are
 * detected by HOLD timers armed when the keys go down, independent of press order.
 *
 * Key codes: KEY_VOLUMEUP=115, KEY_VOLUMEDOWN=114, KEY_POWER=116.
 *
 * --dry-run: detect + log gestures only; do NOT grab devices or emit actions.
 *
 * Fail-safe: if no button device can be opened/grabbed, log and exit non-zero
 * so the supervisor restarts (or, in dry-run, exit non-zero too).
 */

#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <unistd.h>
#include <stdint.h>
#include <signal.h>
#include <sys/epoll.h>
#include <sys/ioctl.h>
#include <sys/timerfd.h>
#include <linux/input.h>
#include <linux/uinput.h>

#ifndef KEY_VOLUMEUP
#define KEY_VOLUMEUP 115
#endif
#ifndef KEY_VOLUMEDOWN
#define KEY_VOLUMEDOWN 114
#endif
#ifndef KEY_POWER
#define KEY_POWER 116
#endif

#define CONFIG_PATH "/data/adb/loop-speaker-mode/config"
#define ACT_PATH    "/data/adb/loop-speaker-mode/scripts/loop-act"

/* sane MT6877 defaults; overridden by config name match at runtime */
#define DEF_KEYPAD_NAME "mtk-kpd"
#define DEF_POWER_NAME  "mtk-pmic-keys"

#define MAX_DEVS 8
#define MAX_SIL  4          /* touchscreen / silence devices grabbed-and-dropped */

/* config-tunable thresholds (ms) */
static long vdtap_ms        = 280;     /* max gap between two taps of a vol key = skip */
static long pair_hold_ms    = 3000;
static long mode_hold_ms    = 5000;
static long power_off_ms    = 2500;    /* power held alone this long = shutdown */
static long vol_ramp_delay_ms = 500;   /* hold a volume key this long before ramping */
static long vol_ramp_int_ms   = 120;   /* then nudge again every this many ms */
static int  grab_touch        = 1;     /* GRAB_TOUCH: silence touchscreen in dumb mode */

static char cfg_keypad[128] = "";  /* INPUT_KEYPAD from config (device name) */
static char cfg_power[128]  = "";  /* INPUT_POWER  from config */

static int dry_run = 0;
static volatile sig_atomic_t running = 1;

/* ---- small logging helper (logcat-style line to stdout) ---- */
static void logln(const char *fmt, ...) {
    char buf[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    fprintf(stdout, "loopkeyd: %s\n", buf);
    fflush(stdout);
}

static void on_sig(int s) { (void)s; running = 0; }

/* ---- config parsing: read KEY=VALUE lines, strip quotes/comments ---- */
static void cfg_str(const char *line, const char *key, char *out, size_t n) {
    size_t kl = strlen(key);
    if (strncmp(line, key, kl) != 0 || line[kl] != '=') return;
    const char *v = line + kl + 1;
    while (*v == ' ' || *v == '"' || *v == '\'') v++;
    size_t i = 0;
    while (v[i] && v[i] != '"' && v[i] != '\'' && v[i] != '\n' &&
           v[i] != '\r' && v[i] != ' ' && v[i] != '#' && i + 1 < n) {
        out[i] = v[i];
        i++;
    }
    out[i] = '\0';
}

static void cfg_long(const char *line, const char *key, long *out) {
    size_t kl = strlen(key);
    if (strncmp(line, key, kl) != 0 || line[kl] != '=') return;
    char tmp[64] = "";
    cfg_str(line, key, tmp, sizeof(tmp));
    if (tmp[0]) {
        char *end;
        long v = strtol(tmp, &end, 10);
        if (end != tmp) *out = v;
    }
}

static void load_config(void) {
    FILE *f = fopen(CONFIG_PATH, "r");
    if (!f) return;
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        if (line[0] == '#') continue;
        cfg_str(line, "INPUT_KEYPAD", cfg_keypad, sizeof(cfg_keypad));
        cfg_str(line, "INPUT_POWER", cfg_power, sizeof(cfg_power));
        cfg_long(line, "VOL_DOUBLETAP_MS", &vdtap_ms);
        cfg_long(line, "GESTURE_PAIR_HOLD_MS", &pair_hold_ms);
        cfg_long(line, "GESTURE_MODE_HOLD_MS", &mode_hold_ms);
        cfg_long(line, "POWER_SHUTDOWN_MS", &power_off_ms);
        cfg_long(line, "VOL_RAMP_DELAY_MS", &vol_ramp_delay_ms);
        cfg_long(line, "VOL_RAMP_INTERVAL_MS", &vol_ramp_int_ms);
        { long g = grab_touch; cfg_long(line, "GRAB_TOUCH", &g); grab_touch = (int)g; }
    }
    fclose(f);
}

/* ---- capability helpers over an EV_KEY bitmap ---- */
#define HASBIT(arr, b) ((arr[(b) / (8 * sizeof(unsigned long))] >> \
                         ((b) % (8 * sizeof(unsigned long)))) & 1UL)

/* Returns 1 if the fd's device must NEVER be grabbed (touchscreen / headset). */
static int is_excluded(int fd, const char *name) {
    /* headset / jack devices: name-based exclusion */
    if (strstr(name, "Headset") || strstr(name, "Jack")) return 1;

    /* touchscreen: exposes BTN_TOUCH */
    unsigned long keys[(KEY_MAX / (8 * sizeof(unsigned long))) + 1];
    memset(keys, 0, sizeof(keys));
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys) >= 0) {
        if (HASBIT(keys, BTN_TOUCH)) return 1;
    }
    return 0;
}

/* Returns 1 if the fd is a touchscreen (exposes BTN_TOUCH). These are silenced
 * (grabbed + dropped) in dumb mode: this MT6877's FocalTech panel injects
 * KEY_POWER on a firmware tap-wake gesture, which would otherwise reach the
 * framework and turn the screen on. Grabbing it keeps the panel dark. */
static int is_touchscreen(int fd) {
    unsigned long keys[(KEY_MAX / (8 * sizeof(unsigned long))) + 1];
    memset(keys, 0, sizeof(keys));
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys) < 0) return 0;
    return HASBIT(keys, BTN_TOUCH);
}

/* Returns 1 if the device carries any of our button keys and is not excluded. */
static int is_button_dev(int fd, const char *name) {
    if (is_excluded(fd, name)) return 0;
    unsigned long keys[(KEY_MAX / (8 * sizeof(unsigned long))) + 1];
    memset(keys, 0, sizeof(keys));
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys) < 0) return 0;
    return HASBIT(keys, KEY_VOLUMEUP) || HASBIT(keys, KEY_VOLUMEDOWN) ||
           HASBIT(keys, KEY_POWER);
}

/* Returns 1 if the device exposes KEY_POWER (and is grabbable). */
static int has_power(int fd, const char *name) {
    if (is_excluded(fd, name)) return 0;
    unsigned long keys[(KEY_MAX / (8 * sizeof(unsigned long))) + 1];
    memset(keys, 0, sizeof(keys));
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys) < 0) return 0;
    return HASBIT(keys, KEY_POWER);
}

/* ---- device discovery ----
 * Collects every physical-button device to grab into devs[]/dev_names[].
 * Strategy:
 *   1. Match the two configured names (INPUT_KEYPAD, INPUT_POWER) — defaults
 *      "mtk-kpd" / "mtk-pmic-keys". Excluded devices are never grabbed.
 *   2. If neither configured name matched anything, fall back to a
 *      capability scan: grab every non-excluded device exposing a button key.
 * Sets *power_idx to the index of a grabbed device exposing KEY_POWER (or -1).
 * Returns the number of devices collected.
 */
static int collect_button_devs(int *devs, char dev_names[][128], int *power_idx) {
    const char *want[2] = {
        cfg_keypad[0] ? cfg_keypad : DEF_KEYPAD_NAME,
        cfg_power[0]  ? cfg_power  : DEF_POWER_NAME,
    };
    int n = 0;
    *power_idx = -1;

    /* pass 1: configured / default names */
    for (int w = 0; w < 2 && n < MAX_DEVS; w++) {
        if (!want[w][0]) continue;
        DIR *d = opendir("/dev/input");
        if (!d) break;
        struct dirent *e;
        while ((e = readdir(d)) != NULL && n < MAX_DEVS) {
            if (strncmp(e->d_name, "event", 5) != 0) continue;
            char path[64];
            snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
            int fd = open(path, O_RDONLY | O_NONBLOCK);
            if (fd < 0) continue;
            char name[128] = "";
            ioctl(fd, EVIOCGNAME(sizeof(name)), name);

            if (strstr(name, want[w]) && !is_excluded(fd, name)) {
                /* skip if this fd path is already collected (both names could
                 * theoretically match the same device) */
                int dup = 0;
                for (int j = 0; j < n; j++)
                    if (strcmp(dev_names[j], name) == 0) { dup = 1; break; }
                if (!dup) {
                    logln("matched \"%s\" -> %s (\"%s\")", want[w], path, name);
                    devs[n] = fd;
                    snprintf(dev_names[n], 128, "%s", name);
                    if (*power_idx < 0 && has_power(fd, name)) *power_idx = n;
                    n++;
                    fd = -1; /* keep open */
                }
            }
            if (fd >= 0) close(fd);
        }
        closedir(d);
    }
    if (n > 0) return n;

    /* pass 2: capability fallback (no configured name matched) */
    DIR *d = opendir("/dev/input");
    if (!d) return 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL && n < MAX_DEVS) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        char name[128] = "";
        ioctl(fd, EVIOCGNAME(sizeof(name)), name);

        if (is_button_dev(fd, name)) {
            logln("cap-match -> %s (\"%s\")", path, name);
            devs[n] = fd;
            snprintf(dev_names[n], 128, "%s", name);
            if (*power_idx < 0 && has_power(fd, name)) *power_idx = n;
            n++;
        } else {
            close(fd);
        }
    }
    closedir(d);
    return n;
}

/* ---- touchscreen discovery: every BTN_TOUCH device, to grab-and-drop ---- */
static int collect_silence_devs(int *sdevs, char sdev_names[][128]) {
    int n = 0;
    DIR *d = opendir("/dev/input");
    if (!d) return 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL && n < MAX_SIL) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;
        char name[128] = "";
        ioctl(fd, EVIOCGNAME(sizeof(name)), name);
        if (is_touchscreen(fd)) {
            logln("silence -> %s (\"%s\")", path, name);
            sdevs[n] = fd;
            snprintf(sdev_names[n], 128, "%s", name);
            n++;
        } else {
            close(fd);
        }
    }
    closedir(d);
    return n;
}

/* ---- uinput virtual device for volume re-injection ---- */
static int uinput_setup(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -1;

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0) goto fail;
    if (ioctl(fd, UI_SET_KEYBIT, KEY_VOLUMEUP) < 0) goto fail;
    if (ioctl(fd, UI_SET_KEYBIT, KEY_VOLUMEDOWN) < 0) goto fail;

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "loopkeyd-vol");
    uidev.id.bustype = BUS_VIRTUAL;
    uidev.id.vendor  = 0x1209;
    uidev.id.product = 0x10b;
    uidev.id.version = 1;
    if (write(fd, &uidev, sizeof(uidev)) < 0) goto fail;
    if (ioctl(fd, UI_DEV_CREATE) < 0) goto fail;
    return fd;
fail:
    close(fd);
    return -1;
}

static void uinput_tap(int fd, int code) {
    struct input_event ev[3];
    memset(ev, 0, sizeof(ev));
    ev[0].type = EV_KEY; ev[0].code = code; ev[0].value = 1;
    ev[1].type = EV_KEY; ev[1].code = code; ev[1].value = 0;
    ev[2].type = EV_SYN; ev[2].code = SYN_REPORT; ev[2].value = 0;
    if (write(fd, ev, sizeof(ev)) < 0)
        logln("uinput write failed: %s", strerror(errno));
}

/* ---- action emission via the shell dispatcher ---- */
static void emit(const char *action) {
    logln("ACTION %s", action);
    if (dry_run) return;
    char cmd[256];
    snprintf(cmd, sizeof(cmd), "sh %s %s", ACT_PATH, action);
    int rc = system(cmd);
    if (rc != 0) logln("loop-act %s returned %d", action, rc);
}

/* ---- timerfd helpers ---- */
static void timer_arm(int tfd, long ms) {
    struct itimerspec its;
    memset(&its, 0, sizeof(its));
    its.it_value.tv_sec  = ms / 1000;
    its.it_value.tv_nsec = (ms % 1000) * 1000000L;
    timerfd_settime(tfd, 0, &its, NULL);
}
static void timer_disarm(int tfd) {
    struct itimerspec its;
    memset(&its, 0, sizeof(its));
    timerfd_settime(tfd, 0, &its, NULL);
}
static void drain_timer(int tfd) {
    uint64_t x;
    while (read(tfd, &x, sizeof(x)) == sizeof(x)) { /* drain */ }
}

int main(int argc, char **argv) {
    for (int i = 1; i < argc; i++)
        if (strcmp(argv[i], "--dry-run") == 0) dry_run = 1;

    load_config();
    logln("start (dry_run=%d) vdtap=%ldms pair=%ldms mode=%ldms poweroff=%ldms",
          dry_run, vdtap_ms, pair_hold_ms, mode_hold_ms, power_off_ms);

    signal(SIGTERM, on_sig);
    signal(SIGINT, on_sig);

    int devs[MAX_DEVS];
    char dev_names[MAX_DEVS][128];
    int power_idx = -1;
    int ndev = collect_button_devs(devs, dev_names, &power_idx);

    if (ndev == 0) {
        logln("FATAL: no button input devices found");
        return 2;
    }
    if (power_idx < 0) {
        logln("FATAL: no grabbable device exposes KEY_POWER");
        for (int i = 0; i < ndev; i++) close(devs[i]);
        return 2;
    }
    logln("grabbed %d button device(s), power on \"%s\"",
          ndev, dev_names[power_idx]);

    /* touchscreen(s) to silence: grabbed-and-dropped so a firmware tap-wake
     * gesture can't reach the framework and light the panel in dumb mode. */
    int sdevs[MAX_SIL];
    char sdev_names[MAX_SIL][128];
    int nsil = 0;

    int ufd = -1;
    if (!dry_run) {
        for (int i = 0; i < ndev; i++) {
            if (ioctl(devs[i], EVIOCGRAB, 1) < 0) {
                logln("FATAL: EVIOCGRAB failed on \"%s\": %s",
                      dev_names[i], strerror(errno));
                /* release any already-grabbed devices before bailing */
                for (int j = 0; j < i; j++) ioctl(devs[j], EVIOCGRAB, 0);
                return 3;
            }
        }
        if (grab_touch) {
            nsil = collect_silence_devs(sdevs, sdev_names);
            for (int i = 0; i < nsil; i++) {
                if (ioctl(sdevs[i], EVIOCGRAB, 1) < 0) {
                    /* non-fatal: lose the no-wake guarantee but keep buttons working */
                    logln("WARN: EVIOCGRAB failed on touchscreen \"%s\": %s",
                          sdev_names[i], strerror(errno));
                    close(sdevs[i]);
                    sdevs[i] = sdevs[--nsil];
                    i--;
                }
            }
            logln("silenced %d touchscreen device(s)", nsil);
        }
        ufd = uinput_setup();
        if (ufd < 0) {
            logln("FATAL: uinput setup failed: %s", strerror(errno));
            return 4;
        }
    }

    /* timers: vol double-tap window, pair-hold, mode-hold, power-off hold, vol-ramp */
    int t_vtap = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK);
    int t_pair = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK);
    int t_mode = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK);
    int t_pwr  = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK);
    int t_vrep = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK);

    int ep = epoll_create1(0);
    struct epoll_event ev;
#define EP_ADD(fd, tag) do { ev.events = EPOLLIN; ev.data.u32 = (tag); \
                             epoll_ctl(ep, EPOLL_CTL_ADD, (fd), &ev); } while (0)
    /* tags 0..ndev-1 are grabbed button fds; TAG_SIL..+nsil are silenced
     * touchscreens (drained + dropped); timers use high tags. */
    enum { TAG_SIL = MAX_DEVS, TAG_T_VTAP = 100, TAG_T_PAIR, TAG_T_MODE,
           TAG_T_PWR, TAG_T_VREP };
    for (int i = 0; i < ndev; i++) EP_ADD(devs[i], (uint32_t)i);
    for (int i = 0; i < nsil; i++) EP_ADD(sdevs[i], (uint32_t)(TAG_SIL + i));
    EP_ADD(t_vtap, TAG_T_VTAP);
    EP_ADD(t_pair, TAG_T_PAIR);
    EP_ADD(t_mode, TAG_T_MODE);
    EP_ADD(t_pwr, TAG_T_PWR);
    EP_ADD(t_vrep, TAG_T_VREP);
#undef EP_ADD

    /* GLOBAL gesture state — keyed by keycode across ALL grabbed devices.
     * VOLUMEUP and VOLUMEDOWN can arrive on different fds; combos still work. */
    int vup_down = 0, vdn_down = 0, pwr_down = 0;
    int pwr_consumed = 0;       /* power was part of mode combo -> no tap on release */
    int pwr_long = 0;           /* power held past shutdown threshold -> not a tap */
    int held_vol = 0;           /* volume key code held for ramp (0 = none) */
    int ramping = 0;            /* held_vol has begun ramping (release won't count a tap) */
    int vtap_key = 0;           /* vol key from a completed short tap, awaiting double-tap */
    int vtap_consumed = 0;      /* current vol press was the 2nd tap (skip) -> release is a no-op */

    while (running) {
        struct epoll_event evs[8 + MAX_DEVS + MAX_SIL];
        int n = epoll_wait(ep, evs, 8 + MAX_DEVS + MAX_SIL, -1);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        for (int i = 0; i < n; i++) {
            uint32_t tag = evs[i].data.u32;

            /* silenced touchscreen: drain and drop everything (incl. the
             * firmware tap-wake KEY_POWER) so the panel never lights. */
            if (tag >= TAG_SIL && tag < TAG_SIL + (uint32_t)nsil) {
                int sfd = sdevs[tag - TAG_SIL];
                struct input_event se;
                while (read(sfd, &se, sizeof(se)) == (ssize_t)sizeof(se)) { /* drop */ }
                continue;
            }

            if (tag == TAG_T_VREP) {
                drain_timer(t_vrep);
                /* hold-to-ramp: a single volume key held past the delay nudges
                 * repeatedly until release. Bail if a combo formed meanwhile. */
                if (held_vol && !pwr_down &&
                    ((held_vol == KEY_VOLUMEUP   && vup_down && !vdn_down) ||
                     (held_vol == KEY_VOLUMEDOWN && vdn_down && !vup_down))) {
                    ramping = 1;       /* this press is a hold-ramp, not a tap */
                    if (!dry_run && ufd >= 0) uinput_tap(ufd, held_vol);
                    logln("ramp %s", held_vol == KEY_VOLUMEUP ? "VOLUP" : "VOLDN");
                    timer_arm(t_vrep, vol_ramp_int_ms);
                } else {
                    held_vol = 0;
                    ramping = 0;
                }
                continue;
            }

            if (tag == TAG_T_VTAP) {
                drain_timer(t_vtap);
                /* double-tap window closed with no second tap: it was a lone volume
                 * tap -> apply exactly one volume step now. */
                if (vtap_key) {
                    if (!dry_run && ufd >= 0) uinput_tap(ufd, vtap_key);
                    logln("vol tap %s", vtap_key == KEY_VOLUMEUP ? "VOLUP" : "VOLDN");
                    vtap_key = 0;
                }
                continue;
            }
            if (tag == TAG_T_PAIR) {
                drain_timer(t_pair);
                /* pair = BOTH volumes for pair_hold_ms with power UP. If power is
                 * also down this is the mode-toggle combo forming (pair is a prefix
                 * of mode); let t_mode handle it instead of firing pairing here. */
                if (vup_down && vdn_down && !pwr_down) {
                    emit("pair_open");
                }
                continue;
            }
            if (tag == TAG_T_MODE) {
                drain_timer(t_mode);
                /* mode = POWER + VOL-DOWN held for mode_hold_ms (NOT vol-up: that
                 * combo is the PMIC hardware reboot we can't intercept). */
                if (pwr_down && vdn_down && !vup_down) {
                    emit("mode_toggle");
                    pwr_consumed = 1;  /* don't fire play_pause when power is released */
                }
                continue;
            }
            if (tag == TAG_T_PWR) {
                drain_timer(t_pwr);
                pwr_long = 1;  /* held long -> the eventual release is not a tap */
                /* power held alone (no volume) past the shutdown threshold -> power
                 * off the speaker (loop-act chimes "Powering off" then shuts down).
                 * There is NO firmware power menu in dumb mode. If a volume is also
                 * down we're forming the mode combo; do nothing here. */
                if (pwr_down && !vup_down && !vdn_down) {
                    logln("power hold: shutdown");
                    emit("shutdown");
                }
                continue;
            }

            /* a grabbed input device (tag < ndev) became readable */
            int dev_fd = devs[tag];
            struct input_event ie;
            ssize_t r;
            while ((r = read(dev_fd, &ie, sizeof(ie))) == (ssize_t)sizeof(ie)) {
                if (ie.type != EV_KEY) continue;
                int pressed = (ie.value == 1);
                int released = (ie.value == 0);
                /* ignore autorepeat (value==2) */
                if (!pressed && !released) continue;

                if (ie.code == KEY_VOLUMEUP || ie.code == KEY_VOLUMEDOWN) {
                    int is_up = (ie.code == KEY_VOLUMEUP);
                    if (pressed) {
                        int other_down = is_up ? vdn_down : vup_down;
                        if (is_up) vup_down = 1; else vdn_down = 1;
                        logln("key %s down", is_up ? "VOLUP" : "VOLDN");

                        if (other_down) {
                            /* both volumes down -> pair window; if power is also down,
                             * it's the mode combo instead (armed below / in power press). */
                            held_vol = 0; ramping = 0;
                            timer_disarm(t_vrep);
                            timer_arm(t_pair, pair_hold_ms);
                            if (pwr_down) timer_arm(t_mode, mode_hold_ms);
                        } else if (pwr_down) {
                            /* power + vol-down (EITHER press order) = mode combo; no volume
                             * change or tap decode while a combo is forming. */
                            if (!is_up) timer_arm(t_mode, mode_hold_ms);
                        } else if (vtap_key == ie.code) {
                            /* SECOND tap of the same volume key within the window -> skip.
                             * vol-up = next track, vol-down = previous. No volume change. */
                            emit(is_up ? "next" : "prev");
                            vtap_key = 0;
                            vtap_consumed = 1;   /* this press's release is a no-op */
                            timer_disarm(t_vtap);
                        } else {
                            /* first tap (or a tap of the other key while one was pending):
                             * flush any pending single tap of the OTHER key, then defer this
                             * one. Nothing is injected yet — t_vtap decides tap vs double-tap.
                             * Arm the ramp timer so a HELD key still ramps. */
                            if (vtap_key && vtap_key != ie.code) {
                                if (!dry_run && ufd >= 0) uinput_tap(ufd, vtap_key);
                                vtap_key = 0;
                            }
                            held_vol = ie.code; ramping = 0; vtap_consumed = 0;
                            timer_arm(t_vrep, vol_ramp_delay_ms);
                        }
                    } else { /* released */
                        if (is_up) vup_down = 0; else vdn_down = 0;
                        logln("key %s up", is_up ? "VOLUP" : "VOLDN");
                        if (vtap_consumed) {
                            /* release of the 2nd tap (skip already fired) */
                            vtap_consumed = 0;
                            if (held_vol == ie.code) { held_vol = 0; }
                            ramping = 0;
                            timer_disarm(t_vrep);
                        } else if (held_vol == ie.code && ramping) {
                            /* a hold-ramp ended: not a tap */
                            held_vol = 0; ramping = 0;
                            timer_disarm(t_vrep);
                        } else if (held_vol == ie.code) {
                            /* a short tap that didn't ramp: open the double-tap window.
                             * If no 2nd tap arrives, t_vtap applies one volume step. */
                            held_vol = 0;
                            timer_disarm(t_vrep);
                            vtap_key = ie.code;
                            timer_arm(t_vtap, vdtap_ms);
                        }
                        if (!(vup_down && vdn_down)) timer_disarm(t_pair);
                        if (!(pwr_down && vdn_down)) timer_disarm(t_mode);
                    }
                } else if (ie.code == KEY_POWER) {
                    if (pressed) {
                        pwr_down = 1;
                        pwr_long = 0;
                        logln("key POWER down");
                        /* power joining a held volume = combo forming, not a ramp */
                        held_vol = 0; ramping = 0;
                        timer_disarm(t_vrep);
                        timer_arm(t_pwr, power_off_ms);
                        if (vdn_down && !vup_down) timer_arm(t_mode, mode_hold_ms);
                    } else { /* released */
                        pwr_down = 0;
                        logln("key POWER up");
                        timer_disarm(t_mode);
                        timer_disarm(t_pwr);
                        if (pwr_consumed) {
                            /* mode combo fired -> swallow the release */
                            pwr_consumed = 0;
                        } else if (pwr_long) {
                            /* held past shutdown threshold (shutdown emitted, or a volume
                             * was also down) -> not a tap */
                        } else {
                            /* clean short tap -> play/pause INSTANTLY (no multi-tap wait) */
                            logln("power tap: play_pause");
                            emit("play_pause");
                        }
                    }
                }
            }
        }
    }

    logln("shutting down");
    if (!dry_run) {
        for (int i = 0; i < ndev; i++) {
            ioctl(devs[i], EVIOCGRAB, 0);
        }
        for (int i = 0; i < nsil; i++) ioctl(sdevs[i], EVIOCGRAB, 0);
        if (ufd >= 0) { ioctl(ufd, UI_DEV_DESTROY); close(ufd); }
    }
    for (int i = 0; i < ndev; i++) close(devs[i]);
    for (int i = 0; i < nsil; i++) close(sdevs[i]);
    return 0;
}
