# Cross-compile loopkeyd (arm64) -> ../magisk-module/system/bin/loopkeyd
#
# NDK is auto-located (latest under $ANDROID_SDK_ROOT/ndk), overridable: make NDK=/path.
# The prebuilt toolchain dir is detected at build time (darwin-x86_64, darwin-arm64,
# linux-x86_64, ...). On Apple Silicon the only prebuilt is darwin-x86_64, run via Rosetta 2.

SDK     ?= $(HOME)/Library/Android/sdk
NDK     ?= $(shell ls -d $(SDK)/ndk/* 2>/dev/null | tail -1)
API     ?= 30
TARGET  ?= aarch64-linux-android

PREBUILT := $(notdir $(shell ls -d $(NDK)/toolchains/llvm/prebuilt/* 2>/dev/null | tail -1))
TOOLBIN  := $(NDK)/toolchains/llvm/prebuilt/$(PREBUILT)/bin
CC       := $(TOOLBIN)/$(TARGET)$(API)-clang

OUT := ../magisk-module/system/bin/loopkeyd

.PHONY: all clean check
all: $(OUT)

$(OUT): loopkeyd.c
	@[ -n "$(NDK)" ] || { echo "NDK not found; run tools/setup-ndk.sh"; exit 1; }
	@[ -x "$(CC)" ] || { echo "cross-compiler missing: $(CC)"; exit 1; }
	@mkdir -p $(dir $(OUT))
	$(CC) -O2 -Wall -Wextra -o $(OUT) loopkeyd.c
	@file $(OUT)

check:
	@echo "NDK=$(NDK)"
	@echo "PREBUILT=$(PREBUILT)"
	@echo "CC=$(CC)"

clean:
	rm -f $(OUT)
