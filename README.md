# イグニ DPC（Device Policy Controller）

完全管理端末（Device Owner）向けの最小 DPC です。QR プロビジョニングが終わると、**標準（OEM）のホーム画面**を使い、**設定**・**Play ストア**・**カメラ**・**Chrome** 以外の起動可能アプリは、安全に隠せるものだけ `DevicePolicyManager.setApplicationHidden` で非表示にします（アンインストールはしません。システムランチャーは置き換えません）。

- パッケージ名: `app.igni.dpc`
- アプリ名: `イグニ`
- 管理者コンポーネント: `app.igni.dpc/.AdminReceiver`
- minSdk 26 / targetSdk 35

## 何をするか

プロビジョニング完了時（`GET_PROVISIONING_MODE` → 完全管理端末、続けて `ADMIN_POLICY_COMPLIANCE`）、Device Owner 有効化時、起動完了時に、同じポリシーを冪等に適用します。ユーザー操作は不要です。

**許可リスト（ホームに残す）**

- `com.android.settings`（設定）および OEM Settings パッケージ
- `com.android.vending`（Play ストア）
- カメラ（`com.android.camera2` / `com.android.camera` / `com.google.android.GoogleCamera` および一般的な OEM カメラ）
- `com.android.chrome`（Chrome；安定版が無い場合のみ beta）
- この DPC 自身（`AdminActivity` — 「ポリシーを再適用」用の通常ランチャーアプリアイコン）
- 標準の HOME ランチャー、SystemUI、IME など端末動作に必要なパッケージ

**隠さない安全リスト（例）**

SystemUI、PackageInstaller、PermissionController、Google Play 開発者サービス、セットアップウィザード、Managed Provisioning、デフォルトランチャー、IME（キーボード）、WebView など。これらを隠すと端末が操作不能になるため、起動アイコンがあっても隠しません。

Lock Task（キオスク）は **デフォルトオフ** です。有効にする場合は `app/build.gradle.kts` の `ENABLE_LOCK_TASK` を `true` にしてください。

## ホーム画面（v1.0.3+）

**標準（OEM）ランチャーをそのまま使います。** Igni の 2×2 タイル `HomeActivity` は HOME としては使いません。

- `PolicyApplier` は `addPersistentPreferredActivity` を呼ばず、適用時に `clearPackagePersistentPreferredActivities(admin, packageName)` で過去の HOME 乗っ取りを解除します
- `HomeActivity` はマニフェストで無効化（HOME/DEFAULT フィルタなし）
- `AdminActivity` は通常の `LAUNCHER` アイコン（「ポリシーを再適用」専用）。HOME には強制しません
- 非許可リストのアプリは隠し、ストックランチャーに Settings / Play / Camera / Chrome（と OEM が置くもの）が残るようにします
- ショートカットのサイレント pin は DO でもユーザー確認が必要なことが多く、信頼できないため行いません（hide + 標準ホームに依存）

## 表示ポリシー（v1.0.2+ / 強化 v1.0.3）

`PolicyApplier.apply()`（プロビジョニング完了・起動・再適用時）で次も冪等に適用します。個別パスの失敗はログのみで、適用全体は止めません。

- **ダークモード ON**: `UiModeManager.setNightMode(MODE_NIGHT_YES)` / API 30+ は `setNightModeActivated(true)`、必要なら `Settings.Secure.UI_NIGHT_MODE` も設定
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
F7X3v4acgg_NnG70zRR6n9qdLmWvXGYg1FtPH-SBHG0
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
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": "F7X3v4acgg_NnG70zRR6n9qdLmWvXGYg1FtPH-SBHG0",
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

ランチャーの「イグニ」アイコン（`AdminActivity`）から開くと:

- Device Owner の有効 / 無効
- **ポリシーを再適用** — 起動可能アプリを再スキャンして隠す（HOME 乗っ取り解除・タイムアウト再設定含む）
- **アプリ一覧を表示に戻す** — この DPC が隠したパッケージを再表示（リストは端末内に保存）
- 現在の `SCREEN_OFF_TIMEOUT`（ms）
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
