/*
 * loopkeyd — native button daemon for loop-speaker-mode (Android arm64 / MT6877).
 *
 * Grabs the keypad (volume) and power input devices with EVIOCGRAB, runs a
 * gesture state machine, and re-injects single volume taps through a uinput
 * virtual device so normal volume control stays instant. Recognised gestures
 * are emitted as actions by invoking the shell dispatcher:
 *     sh /data/adb/loop-speaker-mode/scripts/loop-act <action>
 *
 * Gestures (thresholds read from config, defaults below):
 *   vol+/vol- single tap   -> re-inject (instant volume)
 *   vol+/vol- double tap    -> "next" / "prev"           (within DOUBLE_TAP_WINDOW_MS)
 *   power short press (<600ms) -> "play_pause"
 *   power long press         -> release grab / pass through (firmware power menu)
 *   both volumes held        -> "pair_open"   (GESTURE_PAIR_HOLD_MS)
 *   all three held           -> "mode_toggle" (GESTURE_MODE_HOLD_MS)
 *
 * Key codes (standard Linux input codes; confirm against on-device getevent later):
 *   KEY_VOLUMEUP=115, KEY_VOLUMEDOWN=114, KEY_POWER=116.
 *
 * --dry-run: detect + log gestures only; do NOT grab devices or emit actions.
 *
 * Fail-safe: if a required device cannot be opened or grabbed, log and exit
 * non-zero so the supervisor restarts (or, in dry-run, exit non-zero too).
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

/* ---- device discovery: scan /dev/input/event* and match EVIOCGNAME ---- */
/* match_kind: 0 = keypad (looks for volume keys), 1 = power. */
static int open_input_by_name(const char *want, int match_kind) {
    DIR *d = opendir("/dev/input");
    if (!d) return -1;
    struct dirent *e;
    int found = -1;
    while ((e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;

        char name[128] = "";
        ioctl(fd, EVIOCGNAME(sizeof(name)), name);

        int ok = 0;
        if (want && want[0]) {
            ok = (strstr(name, want) != NULL);
        } else {
            /* no config name: capability-based fallback */
            unsigned long keys[(KEY_MAX / (8 * sizeof(unsigned long))) + 1];
            memset(keys, 0, sizeof(keys));
            if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys) >= 0) {
#define HASKEY(k) ((keys[(k) / (8 * sizeof(unsigned long))] >> \
                    ((k) % (8 * sizeof(unsigned long)))) & 1UL)
                if (match_kind == 0) {
                    ok = HASKEY(KEY_VOLUMEUP) && HASKEY(KEY_VOLUMEDOWN);
                } else {
                    ok = HASKEY(KEY_POWER) && !HASKEY(KEY_VOLUMEUP);
                }
#undef HASKEY
            }
        }
        if (ok) {
            logln("matched %s -> %s (\"%s\")",
                  match_kind == 0 ? "keypad" : "power", path, name);
            found = fd;
            break;
        }
        close(fd);
    }
    closedir(d);
    return found;
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

    int kfd = open_input_by_name(cfg_keypad[0] ? cfg_keypad : DEF_KEYPAD_NAME, 0);
    if (kfd < 0 && !cfg_keypad[0]) kfd = open_input_by_name("", 0); /* cap fallback */
    int pfd = open_input_by_name(cfg_power[0] ? cfg_power : DEF_POWER_NAME, 1);
    if (pfd < 0 && !cfg_power[0]) pfd = open_input_by_name("", 1);  /* cap fallback */

    if (kfd < 0 || pfd < 0) {
        logln("FATAL: could not open input devices (keypad=%d power=%d)", kfd, pfd);
        return 2;
    }

    int ufd = -1;
    if (!dry_run) {
        if (ioctl(kfd, EVIOCGRAB, 1) < 0 || ioctl(pfd, EVIOCGRAB, 1) < 0) {
            logln("FATAL: EVIOCGRAB failed: %s", strerror(errno));
            return 3;
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
    enum { TAG_KEY = 1, TAG_PWR_DEV, TAG_T_DBL, TAG_T_PAIR, TAG_T_MODE, TAG_T_PWR };
    EP_ADD(kfd, TAG_KEY);
    EP_ADD(pfd, TAG_PWR_DEV);
    EP_ADD(t_dbl, TAG_T_DBL);
    EP_ADD(t_pair, TAG_T_PAIR);
    EP_ADD(t_mode, TAG_T_MODE);
    EP_ADD(t_pwr, TAG_T_PWR);
#undef EP_ADD

    /* gesture state */
    int vup_down = 0, vdn_down = 0, pwr_down = 0;
    int pending_dbl = 0;        /* a volume key awaiting its second tap */
    int pending_code = 0;       /* which volume key is pending */
    int pwr_passthrough = 0;    /* grab released for power menu */

    while (running) {
        struct epoll_event evs[8];
        int n = epoll_wait(ep, evs, 8, -1);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        for (int i = 0; i < n; i++) {
            uint32_t tag = evs[i].data.u32;

            if (tag == TAG_T_DBL) {
                drain_timer(t_dbl);
                /* window closed without a second tap: it was a single tap.
                 * Volume was already re-injected on press, so just clear. */
                pending_dbl = 0;
                pending_code = 0;
                continue;
            }
            if (tag == TAG_T_PAIR) {
                drain_timer(t_pair);
                if (vup_down && vdn_down) {
                    emit("pair_open");
                    /* consume: cancel any pending single/double for these keys */
                    pending_dbl = 0;
                }
                continue;
            }
            if (tag == TAG_T_MODE) {
                drain_timer(t_mode);
                if (vup_down && vdn_down && pwr_down) emit("mode_toggle");
                continue;
            }
            if (tag == TAG_T_PWR) {
                drain_timer(t_pwr);
                /* power held past short-press threshold -> long press.
                 * Release grab so firmware power menu / shutdown works. */
                if (pwr_down && !(vup_down && vdn_down)) {
                    logln("power long-press: passthrough");
                    if (!dry_run && !pwr_passthrough) {
                        ioctl(pfd, EVIOCGRAB, 0);
                        pwr_passthrough = 1;
                    }
                }
                continue;
            }

            /* an input device became readable */
            int dev_fd = (tag == TAG_KEY) ? kfd : pfd;
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
                        if (is_up) vup_down = 1; else vdn_down = 1;

                        if (vup_down && vdn_down) {
                            /* both volumes: candidate for pair / mode combos */
                            timer_arm(t_pair, pair_hold_ms);
                            if (pwr_down) timer_arm(t_mode, mode_hold_ms);
                            /* don't treat as volume nudge while combo forming */
                            pending_dbl = 0;
                        } else {
                            /* single volume press: instant re-inject now */
                            if (!dry_run && ufd >= 0) uinput_tap(ufd, ie.code);
                            else logln("dry: reinject %s",
                                       is_up ? "VOLUP" : "VOLDOWN");
                            /* double-tap detection */
                            if (pending_dbl && pending_code == ie.code) {
                                emit(is_up ? "next" : "prev");
                                pending_dbl = 0;
                                pending_code = 0;
                                timer_disarm(t_dbl);
                            } else {
                                pending_dbl = 1;
                                pending_code = ie.code;
                                timer_arm(t_dbl, double_tap_ms);
                            }
                        }
                    } else { /* released */
                        if (is_up) vup_down = 0; else vdn_down = 0;
                        if (!(vup_down && vdn_down)) {
                            timer_disarm(t_pair);
                            timer_disarm(t_mode);
                        }
                    }
                } else if (ie.code == KEY_POWER) {
                    if (pressed) {
                        pwr_down = 1;
                        timer_arm(t_pwr, power_short_ms);
                        if (vup_down && vdn_down) timer_arm(t_mode, mode_hold_ms);
                    } else { /* released */
                        pwr_down = 0;
                        timer_disarm(t_mode);
                        if (pwr_passthrough) {
                            /* re-grab after passthrough power event finished */
                            if (!dry_run) ioctl(pfd, EVIOCGRAB, 1);
                            pwr_passthrough = 0;
                            logln("power released: re-grabbed");
                        } else {
                            /* released before long-press fired -> short press */
                            timer_disarm(t_pwr);
                            emit("play_pause");
                        }
                    }
                }
            }
        }
    }

    logln("shutting down");
    if (!dry_run) {
        ioctl(kfd, EVIOCGRAB, 0);
        if (!pwr_passthrough) ioctl(pfd, EVIOCGRAB, 0);
        if (ufd >= 0) { ioctl(ufd, UI_DEV_DESTROY); close(ufd); }
    }
    close(kfd);
    close(pfd);
    return 0;
}
