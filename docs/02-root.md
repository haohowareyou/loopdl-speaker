# Step 2: Root with Magisk

After unlocking (see [docs/01-unlock.md](01-unlock.md)), root is straightforward via
Magisk and `fastboot flash init_boot`. This device uses `init_boot`, **not** `boot`,
as the ramdisk partition (standard on Android 13+ A/B devices with GKI kernels).

---

## What you need

- Magisk APK (v30.x tested; download from [github.com/topjohnwu/Magisk](https://github.com/topjohnwu/Magisk/releases))
- The stock `init_boot_a.img` for your firmware version  
  (dump it yourself: `adb shell su -c 'dd if=/dev/block/by-name/init_boot_a' > init_boot_a.img`, or use the partitions backup from recovery step 0)
- `adb` and `fastboot` on your Mac/PC

---

## Step-by-step

### 1. Install Magisk on the device

```bash
adb install Magisk-v30.6.apk
```

### 2. Patch init_boot

- Open the Magisk app on the device
- Tap **Install → Select and Patch a File**
- Pick your `init_boot_a.img`
- Magisk produces `magisk_patched-XXXXX.img` in `/sdcard/Download/`

Pull it back to your Mac:

```bash
adb pull /sdcard/Download/magisk_patched-30600_XXXXX.img magisk_patched.img
```

### 3. Flash the patched image

Boot the device into fastboot mode:

```bash
adb reboot bootloader
```

Flash to the current slot (the device is A/B; check active slot first if unsure):

```bash
fastboot getvar current-slot          # → current-slot: a
fastboot flash init_boot magisk_patched.img
fastboot reboot
```

> Why `init_boot` and not `boot`? On this device (Android 15, GKI), the generic
> ramdisk lives in `init_boot_a/b`, not the kernel partition. Patching `boot` instead
> would not inject Magisk and would leave the device unrooted.

### 4. Verify root

```bash
adb shell su -c id
```

Expected:

```
uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0
```

The Magisk app should also show "Installed" with your version number.

---

## To un-root later

Flash the original (unpatched) `init_boot` image over fastboot:

```bash
fastboot flash init_boot init_boot_a.img   # your stock image
fastboot reboot
```

Or see [docs/recovery.md](recovery.md) for the full restore-to-stock recipe.
