# イグニ DPC（Device Policy Controller）

完全管理端末（Device Owner）向けの最小 DPC です。QR プロビジョニングが終わると、**ドック型ホーム**（設定・Playストア・Chrome・カメラ）を使い、許可リスト外について次を適用します。

- **ユーザーアプリ**（非システム）: Device Owner として `PackageInstaller.uninstall` で**サイレントアンインストール**（容量を解放）
- **システムアプリ**: アンインストールせず `DevicePolicyManager.setApplicationHidden(true)` で**非表示のみ**

- パッケージ名: `app.igni.dpc`
- アプリ名: `イグニ`
- 管理者コンポーネント: `app.igni.dpc/.AdminReceiver`
- minSdk 26 / targetSdk 35

## 何をするか

プロビジョニング完了時（`GET_PROVISIONING_MODE` → 完全管理端末、続けて `ADMIN_POLICY_COMPLIANCE`）、Device Owner 有効化時、起動完了時に、同じポリシーを冪等に適用します。ユーザー操作は不要です。

**許可リスト（残す・消さない）**

- `com.android.settings`（設定）および OEM Settings パッケージ
- `com.android.vending`（Play ストア）
- カメラ（静的 OEM リスト + **動的検出**: `IMAGE_CAPTURE` / `STILL_IMAGE_CAMERA` / `VIDEO_CAMERA` ハンドラ、および packageName に `camera` を含む MAIN/LAUNCHER アプリ。適用時に必ず unhide）
- `com.android.chrome`（Chrome；安定版が無い場合のみ beta）
- この DPC 自身（`HomeActivity` が HOME。`AdminActivity` はホームの「管理」から開く）
- SystemUI、IME など端末動作に必要なパッケージ

**隠さない安全リスト（例）**

SystemUI、PackageInstaller、PermissionController、Google Play 開発者サービス、セットアップウィザード、Managed Provisioning、デフォルトランチャー、IME（キーボード）、WebView など。これらを隠したり消したりすると端末が操作不能になるため、対象外です。

Lock Task（キオスク）は **デフォルトオフ** です。有効にする場合は `app/build.gradle.kts` の `ENABLE_LOCK_TASK` を `true` にしてください。

## ホーム画面（v1.0.6 ドック型）

**通常のホームに見えるドック UI** を `HomeActivity` で提供します（管理パネル風の 2×2 タイル／大きな「イグニ」バナーは使いません）。

- 全画面の暗いニュートラル背景（グラデーション）＋**画面下部の横一列ドック**（左→右固定）:
  1. 設定　2. Playストア　3. Chrome　4. カメラ
- アイコンは可能なら `PackageManager` の実アプリアイコン。日本語ラベル付き。ステータスバーは表示したまま（immersive 固定オフ）
- 端の小さな「管理」または空領域の長押しで `AdminActivity`（再適用）
- マニフェスト: `HomeActivity` に `MAIN` + `HOME` + `DEFAULT`。`AdminActivity` に `LAUNCHER` は付けない
- `PolicyApplier.apply()`: 自パッケージの `clearPackagePersistentPreferredActivities` のあと `addPersistentPreferredActivity` でこの Home を再設定
- 許可リスト外のユーザーアプリ・アンインストール／システム非表示は v1.0.5 と同じ

## カメラ保護（v1.0.4）

一部 OEM ではカメラのパッケージ名が静的リストに無く、hide ポリシー適用後にホームからカメラが消えることがありました。v1.0.4 では:

- `KeepPackages.detectCameraPackages()` が次を **動的** に keep 対象にします
  - `MediaStore.ACTION_IMAGE_CAPTURE` / `android.media.action.IMAGE_CAPTURE` / `STILL_IMAGE_CAMERA` / `VIDEO_CAMERA` を解決するパッケージ
  - MAIN/LAUNCHER があり packageName に `camera` を含むパッケージ（大文字小文字不問）
  - 既存の静的リスト（Sony / Xiaomi / Transsion / Sharp / FCNT / Kyocera 等を拡充）
- `PolicyApplier.apply()` のたびに検出したカメラを **明示的に unhide** し、`HiddenStore` からも除去
- 管理画面（`AdminActivity`）に検出カメラパッケージ一覧を表示

## アンインストール vs 非表示（v1.0.5）

`PolicyApplier.apply()` は許可リスト外のパッケージを次のように扱います。

| 種別 | 判定 | 動作 |
|---|---|---|
| ユーザーアプリ | `FLAG_SYSTEM` / `FLAG_UPDATED_SYSTEM_APP` なし | **サイレントアンインストール**（`PackageInstaller.uninstall`、結果はログ） |
| システムアプリ | 上記フラグあり | **非表示のみ**（`setApplicationHidden(true)`）。起動可能なもののみ |

- 許可リスト・DPC 自身・IME・SystemUI 等（`KeepPackages.shouldKeep`）は絶対に消さない／隠さない
- アンインストールしたパッケージは `HiddenStore` から除去（もう無いため）。システム非表示は追跡を継続
- 「アプリ一覧を表示に戻す」は **隠したシステムアプリのみ**復元可能。アンインストール済みユーザーアプリは復元不可（Play 等から再インストール）

## 表示ポリシー（v1.0.2+ / 強化 v1.0.3 / ダーク強化 v1.0.6）

`PolicyApplier.apply()`（プロビジョニング完了・起動・再適用時）で次も冪等に適用します。個別パスの失敗はログのみで、適用全体は止めません。

- **ダークモード ON（強化）**:
  - `UiModeManager.setNightMode(MODE_NIGHT_YES)` / API 30+ は `setNightModeActivated(true)`（反射）
  - `Settings.Secure.ui_night_mode = 2`
  - 追加で安全な OEM 系キーを試行: `dark_theme` / `night_mode` / `ui_night_mode`（Secure・System・Global）
  - 結果を永続化し、管理画面に **Android SDK・API 有無・適用結果（success/fail/unsupported）** を表示
  - **SDK &lt; 29**（Android 10 未満）ではシステム暗色テーマが無い場合がある旨を日本語で注記（Sharp AQUOS sense3 の Android 9 など）
- **画面オフ 30 分**:
  - `Settings.System.putInt(..., SCREEN_OFF_TIMEOUT, 1_800_000)` のあと **読み戻してログ**
  - 利用可能なら反射で `DevicePolicyManager.setSystemSetting(admin, SCREEN_OFF_TIMEOUT, "1800000")`（DO SystemApi）も試す
  - `setMaximumTimeToLock(30 min)` は補完として残す。一部 OEM ではキーガード／画面オフと干渉しうるため、**SCREEN_OFF_TIMEOUT が残ることを優先**
  - 管理画面（`AdminActivity`）に現在の `SCREEN_OFF_TIMEOUT`（ms）を表示し、再適用後に確認できる

## リリース APK のビルド

JDK 17 以上と Android SDK（compileSdk 35 / build-tools 35）が必要です。

```bash
# SDK の場所（例）
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# リポジトリ直下に local.properties を置いてもよい
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleRelease
```

成果物:

`app/build/outputs/apk/release/app-release.apk`

プロジェクト同梱のキーストア `keystore/igni-release.jks`（パスワードは `keystore.properties`）で署名します。ローカル／CI 用です。本番配布では専用のキーストアに差し替えてください。

キーストアを自分で作る場合:

```bash
keytool -genkeypair -v \
  -keystore keystore/igni-release.jks \
  -alias igni \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass '<password>' -keypass '<password>' \
  -dname "CN=Igni DPC, O=Igni, C=JP"
```

その後 `keystore.properties` のパスワードとエイリアスを合わせてください。

## 署名チェックサム（QR 用）

`android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` は、**APK の署名証明書（DER）の SHA-256** を URL-safe Base64（パディングなし）にしたものです。

同梱キーストア向けの値:

```
yOZEhRr9nIbif0_vEKh4PE1exHHPPUQeDOuyGnkuOwA
```

キーストアを差し替えたら `./scripts/signature-checksum.sh` で必ず作り直してください。

### apksigner を使う

```bash
# SHA-256 ダイジェスト（コロン付き hex）を取得
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk

# hex をバイナリに戻し、URL-safe Base64（パディングなし）へ
# xxd がある場合:
echo '<SHA-256 hex without colons>' | xxd -r -p | openssl base64 -A | tr '+/' '-_' | tr -d '='

# xxd が無い場合:
python3 -c 'import binascii,base64; print(base64.urlsafe_b64encode(binascii.unhexlify("<hex>")).decode().rstrip("="))'
```

`apksigner` は Android SDK の `build-tools/<version>/apksigner` にあります。

### openssl / keytool を使う（キーストアから直接）

```bash
keytool -exportcert -alias igni -keystore keystore/igni-release.jks \
  | openssl dgst -binary -sha256 \
  | openssl base64 -A | tr '+/' '-_' | tr -d '='
```

または:

```bash
./scripts/signature-checksum.sh
./scripts/signature-checksum.sh app/build/outputs/apk/release/app-release.apk
```

キーストアを変えたらチェックサムも必ず作り直してください。

## QR プロビジョニング（Android 10+）

1. 端末を **工場出荷状態にリセット** する。
2. 初期設定のようこそ画面で、同じ場所を **6 回タップ** して QR リーダーを出す。
3. Wi-Fi に接続したあと、次のような JSON を QR にしたものを読み取る。

```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "app.igni.dpc/.AdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "https://example.com/igni-dpc.apk",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": "yOZEhRr9nIbif0_vEKh4PE1exHHPPUQeDOuyGnkuOwA",
  "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true,
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": false
}
```

`PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED` を `true` にすると、プロビジョニング中にシステムアプリが無効化されず、この DPC が後から非表示制御します。

ダウンロード URL は、端末が取得できる HTTPS 上の APK に置き換えてください。

### この DPC が実装しているセットアップフック

| アクション | 動き |
|---|---|
| `android.app.action.GET_PROVISIONING_MODE` | 許可モードに含まれる場合のみ `PROVISIONING_MODE_FULLY_MANAGED_DEVICE` を返す。仕事用プロファイルは選ばない。 |
| `android.app.action.ADMIN_POLICY_COMPLIANCE` | 非表示ポリシーを適用し `RESULT_OK` で終了。セットアップウィザードが完了できる。 |

## 開発用: adb で Device Owner にする

未プロビジョニング（アカウント未追加）の端末:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell dpm set-device-owner app.igni.dpc/.AdminReceiver
```

## 管理画面

ドックホームの「管理」（または空領域の長押し）から `AdminActivity` を開くと:

- Device Owner の有効 / 無効
- **ポリシーを再適用** — 許可リスト外のユーザーアプリをアンインストールし、システム不要アプリを非表示（ドック Home 再設定・ダーク強化・タイムアウト再設定含む）
- **アプリ一覧を表示に戻す** — この DPC が**非表示にしたシステムアプリのみ**再表示（アンインストール済みユーザーアプリは復元不可）
- 現在の `SCREEN_OFF_TIMEOUT`（ms）
- **ダークモード**: SDK バージョン、API 有無、直近の適用結果
- 許可リストの表示

## プロジェクト構成

単一モジュール（`:app`）、Kotlin、Android Gradle Plugin。

```
app/src/main/java/app/igni/dpc/
  AdminReceiver.kt
  GetProvisioningModeActivity.kt
  PolicyComplianceActivity.kt
  HomeActivity.kt
  AdminActivity.kt
  policy/PolicyApplier.kt
  policy/KeepPackages.kt
```
