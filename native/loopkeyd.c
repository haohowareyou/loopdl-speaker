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
 *   vol+/vol- single tap        -> re-inject (volume) after DOUBLE_TAP_WINDOW_MS decode delay
 *   vol+/vol- double tap        -> "next" / "prev"   (within DOUBLE_TAP_WINDOW_MS)
 *   power short press (<600ms)   -> "play_pause"
 *   power long press (no vol)    -> release grab on power fd / pass through
 *   both volumes held            -> "pair_open"   (GESTURE_PAIR_HOLD_MS)
 *   power + vol-down held        -> "mode_toggle" (GESTURE_MODE_HOLD_MS)  [-> full mode]
 *
 * Volume is decoded with a short delay (DOUBLE_TAP_WINDOW_MS): on the first press we
 * do NOT act, so that a second key (other volume = combo, or same key = double tap)
 * can be recognised first. A lone press fires the volume nudge when the window expires.
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

/* config-tunable thresholds (ms) */
static long double_tap_ms   = 300;
static long pair_hold_ms    = 3000;
static long mode_hold_ms    = 5000;
static long power_short_ms  = 600;

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
        cfg_long(line, "DOUBLE_TAP_WINDOW_MS", &double_tap_ms);
        cfg_long(line, "GESTURE_PAIR_HOLD_MS", &pair_hold_ms);
        cfg_long(line, "GESTURE_MODE_HOLD_MS", &mode_hold_ms);
        cfg_long(line, "POWER_SHORT_MS", &power_short_ms);
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
            int fd = open(path, O_RDONLY);
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
    logln("start (dry_run=%d) dbl=%ldms pair=%ldms mode=%ldms pwr=%ldms",
          dry_run, double_tap_ms, pair_hold_ms, mode_hold_ms, power_short_ms);

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
        ufd = uinput_setup();
        if (ufd < 0) {
            logln("FATAL: uinput setup failed: %s", strerror(errno));
            return 4;
        }
    }

    /* timers: double-tap window, pair-hold, mode-hold, power long-press */
    int t_dbl  = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK);
    int t_pair = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK);
    int t_mode = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK);
    int t_pwr  = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK);

    int ep = epoll_create1(0);
    struct epoll_event ev;
#define EP_ADD(fd, tag) do { ev.events = EPOLLIN; ev.data.u32 = (tag); \
                             epoll_ctl(ep, EPOLL_CTL_ADD, (fd), &ev); } while (0)
    /* tags 0..ndev-1 are the grabbed input fds; timers use high tags */
    enum { TAG_T_DBL = 100, TAG_T_PAIR, TAG_T_MODE, TAG_T_PWR };
    for (int i = 0; i < ndev; i++) EP_ADD(devs[i], (uint32_t)i);
    EP_ADD(t_dbl, TAG_T_DBL);
    EP_ADD(t_pair, TAG_T_PAIR);
    EP_ADD(t_mode, TAG_T_MODE);
    EP_ADD(t_pwr, TAG_T_PWR);
#undef EP_ADD

    /* GLOBAL gesture state — keyed by keycode across ALL grabbed devices.
     * VOLUMEUP and VOLUMEDOWN can arrive on different fds; combos still work. */
    int vup_down = 0, vdn_down = 0, pwr_down = 0;
    int vol_pending = 0;        /* a volume key code awaiting decode (0 = none) */
    int pwr_passthrough = 0;    /* grab released on power fd for power menu */
    int pwr_consumed = 0;       /* power was part of mode combo -> no play_pause on release */
    int pwr_long = 0;           /* power held past short threshold -> not a tap */

    while (running) {
        struct epoll_event evs[8 + MAX_DEVS];
        int n = epoll_wait(ep, evs, 8 + MAX_DEVS, -1);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        for (int i = 0; i < n; i++) {
            uint32_t tag = evs[i].data.u32;

            if (tag == TAG_T_DBL) {
                drain_timer(t_dbl);
                /* decode window closed with no second key: a lone volume tap.
                 * Re-inject the nudge now (delayed-volume). */
                if (vol_pending) {
                    if (!dry_run && ufd >= 0) uinput_tap(ufd, vol_pending);
                    else logln("dry: reinject %s",
                               vol_pending == KEY_VOLUMEUP ? "VOLUP" : "VOLDOWN");
                    vol_pending = 0;
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
                    /* consume: cancel any pending single-volume decode */
                    vol_pending = 0;
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
                pwr_long = 1;  /* power is now a long press, not a tap */
                /* power long-press with NO volume down -> hand the power menu to
                 * firmware by releasing the grab on the power fd. If any volume is
                 * down we're forming the mode combo; do nothing. */
                if (pwr_down && !vup_down && !vdn_down) {
                    logln("power long-press: passthrough");
                    if (!dry_run && !pwr_passthrough) {
                        ioctl(devs[power_idx], EVIOCGRAB, 0);
                        pwr_passthrough = 1;
                    }
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

                        if (other_down) {
                            /* both volumes down -> pair/mode combo candidate.
                             * Cancel any pending single-volume decode. */
                            vol_pending = 0;
                            timer_disarm(t_dbl);
                            timer_arm(t_pair, pair_hold_ms);
                            if (pwr_down) timer_arm(t_mode, mode_hold_ms);
                        } else if (vol_pending == ie.code) {
                            /* second tap of the same key within the window -> next/prev */
                            emit(is_up ? "next" : "prev");
                            vol_pending = 0;
                            timer_disarm(t_dbl);
                        } else {
                            /* first press: defer. Decode on t_dbl expiry or a 2nd key. */
                            vol_pending = ie.code;
                            timer_arm(t_dbl, double_tap_ms);
                        }
                    } else { /* released */
                        if (is_up) vup_down = 0; else vdn_down = 0;
                        if (!(vup_down && vdn_down)) {
                            timer_disarm(t_pair);
                        }
                        if (!(pwr_down && vdn_down)) {
                            timer_disarm(t_mode);
                        }
                    }
                } else if (ie.code == KEY_POWER) {
                    if (pressed) {
                        pwr_down = 1;
                        pwr_long = 0;
                        timer_arm(t_pwr, power_short_ms);
                        if (vdn_down && !vup_down) timer_arm(t_mode, mode_hold_ms);
                    } else { /* released */
                        pwr_down = 0;
                        timer_disarm(t_mode);
                        if (pwr_passthrough) {
                            if (!dry_run) ioctl(devs[power_idx], EVIOCGRAB, 1);
                            pwr_passthrough = 0;
                            logln("power released: re-grabbed");
                        } else if (pwr_consumed) {
                            timer_disarm(t_pwr);
                            pwr_consumed = 0;
                        } else if (!pwr_long) {
                            timer_disarm(t_pwr);
                            emit("play_pause");
                        } else {
                            timer_disarm(t_pwr);
                        }
                    }
                }
            }
        }
    }

    logln("shutting down");
    if (!dry_run) {
        for (int i = 0; i < ndev; i++) {
            if (i == power_idx && pwr_passthrough) continue; /* already released */
            ioctl(devs[i], EVIOCGRAB, 0);
        }
        if (ufd >= 0) { ioctl(ufd, UI_DEV_DESTROY); close(ufd); }
    }
    for (int i = 0; i < ndev; i++) close(devs[i]);
    return 0;
}
