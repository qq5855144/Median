# Validation — Median Browser 2.1.6 source patch

Validation date: 2026-07-17

## Verified

- `applicationId` remains `com.xinyv.median.compat`.
- `versionCode` is 69 and `versionName` is `2.1.6`.
- Java syntax sanity passed for all 53 Java files.
- Android XML parsing passed.
- Network security, download retry, omnibox, home-open policy, custom CSS, custom HTML, home-page configuration, Logo markup, generated userscript JavaScript, and static release checks passed.
- The old `我的主页` string exists only as an exact migration sentinel; the active editor example uses the current Logo title and defaults to `Median`.
- Selecting `自定义代码` no longer writes `home_logo_style` or `home_logo_code` before the editor's Save action.

## APK/signing status

A directly updatable APK was not produced in this runtime. Android requires an update to use both the same package name and the same signing identity. The 2.1.5 release certificate SHA-256 is:

`80daf48c091d6174981c2a176360b42ac32463d23ddc9e5f3c95c0951e5e3da9`

The corresponding private keystore is not present in the supplied source archive or runtime. The Android SDK/build-tools are also not installed in this runtime. A newly generated key would create an APK with the same package name but Android would reject it as an in-place update.
