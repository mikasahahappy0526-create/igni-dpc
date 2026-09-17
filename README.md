# イグニ DPC（Device Policy Controller）

完全管理端末（Device Owner）向けの最小 DPC です。QR プロビジョニングが終わると、**端末標準ホーム（Samsung One UI 等）**のまま、許可リスト外について次を適用します。設定・Play・Chrome・LINE・カメラは表示を維持します。

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
- `jp.naver.line.android`（LINE）
- この DPC 自身（`AdminActivity` が LAUNCHER のみ。`HomeActivity` は無効・HOME にしない）
- SystemUI、IME など端末動作に必要なパッケージ

**隠さない安全リスト（例）**

SystemUI、PackageInstaller、PermissionController、Google Play 開発者サービス、セットアップウィザード、Managed Provisioning、デフォルトランチャー、IME（キーボード）、WebView など。これらを隠したり消したりすると端末が操作不能になるため、対象外です。

Lock Task（キオスク）は **デフォルトオフ** です。有効にする場合は `app/build.gradle.kts` の `ENABLE_LOCK_TASK` を `true` にしてください。

## ホーム画面（v1.0.11: 標準ランチャー）

**Igni を HOME にしない**（Galaxy A23 等でドック／管理ホームに固定されて Chrome が使えなくなる問題の修正）。

- マニフェスト: `HomeActivity` は **無効**（`enabled=false`、HOME/DEFAULT フィルタなし）
- `AdminActivity` のみ `MAIN` + `LAUNCHER`（管理・再適用・更新用）
- `PolicyApplier.apply()` のたび: `clearPackagePersistentPreferredActivities(admin, packageName)` のみ。**`addPersistentPreferredActivity` は呼ばない**
- ホームは Samsung One UI / 端末標準ランチャー。許可リストにより設定・Play・Chrome・LINE・カメラがランチャーに残る
- Chrome: 適用時に明示 unhide。lock-task 既定オフ。カスタムホームによるブラウザ intent 横取りなし
- LINE: `jp.naver.line.android` を許可リストに追加し、適用時に明示 unhide。未インストール時は適用後にサイレント APK → Play フォールバック（v1.0.12）

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

- **ダークモード ON（強化 / Samsung One UI v1.0.9）**:
  - `UiModeManager.setNightMode(MODE_NIGHT_YES)` / API 30+ は `setNightModeActivated(true)`（反射）
  - `Settings.Secure.ui_night_mode = 2`
  - **Samsung One UI**: `Settings.System.display_night_theme = 1`（Galaxy A23 等）
  - 追加で安全な OEM 系キーを試行: `dark_theme` / `theme_mode` / `night_mode` / `ui_night_mode`（Secure・System・Global、1 または 2 = ON）
  - Device Owner 時は反射で `DevicePolicyManager.setSystemSetting` / `setSecureSetting`（`ui_night_mode`・`display_night_theme`）も試行
  - 書き込み後に `setApplicationNightMode`（あれば）と Samsung / night 系ブロードキャストをベストエフォート送信
  - 結果を永続化し、管理画面に **Android SDK・API 有無・適用結果・display_night_theme 読み戻し** を表示
  - **SDK &lt; 29**（Android 10 未満）ではシステム暗色テーマが無い場合がある旨を日本語で注記（Sharp AQUOS sense3 の Android 9 など）
- **画面オフ 30 分**:
  - `Settings.System.putInt(..., SCREEN_OFF_TIMEOUT, 1_800_000)` のあと **読み戻してログ**
  - 利用可能なら反射で `DevicePolicyManager.setSystemSetting(admin, SCREEN_OFF_TIMEOUT, "1800000")`（DO SystemApi）も試す
  - `setMaximumTimeToLock(30 min)` は補完として残す。一部 OEM ではキーガード／画面オフと干渉しうるため、**SCREEN_OFF_TIMEOUT が残ることを優先**
  - 管理画面（`AdminActivity`）に現在の `SCREEN_OFF_TIMEOUT`（ms）を表示し、再適用後に確認できる

## Samsung One UI ダークモード（v1.0.9）

Galaxy A23 など One UI では標準の `UiModeManager` / `ui_night_mode` だけではダークが効かないことがあります。v1.0.9 の `applyDarkMode()` は次を追加で試します（いずれもベストエフォート・例外は握りつぶし）。

1. 既存: `UiModeManager` + `Settings.Secure.ui_night_mode=2`
2. Samsung クラシック: `Settings.System.putInt(cr, "display_night_theme", 1)`
3. 既知の安全キー: `dark_theme` / `theme_mode` / `night_mode`（System/Secure、1 または 2 = ON）
4. 反射 `DPM.setSystemSetting` / `setSecureSetting` で `ui_night_mode` と `display_night_theme`
5. 書き込み後: `setApplicationNightMode`（あれば）＋ Samsung / night 系ブロードキャスト
6. 管理画面: 適用後の `display_night_theme` 読み戻しが 1 かどうかを表示

v1.0.8 のマナーモード＋音量 0、更新ボタン、アンインストールは維持しています。

## v1.0.10（Galaxy A23 UX）

- **標準ホーム**: Igni HOME を完全撤廃。再適用で persistent preferred をクリアし、Samsung ランチャーに戻す
- **Chrome 利用可**: `com.android.chrome` を必ず unhide / 非アンインストール。lock-task なし
- **ダーク（Settings 反映）**: 優先で `Settings.System.display_night_theme=1` を書き込み、読み戻しが 1 であることを管理画面に表示。併せて UiModeManager / `ui_night_mode=2` / DPM 反射設定
- versionCode **11** / versionName **1.0.10**

## v1.0.11（LINE 保持）

- **LINE 利用可**: `jp.naver.line.android` を許可リストに追加し、適用時に明示 unhide。Play ストアからインストールした LINE は再適用後も保持
- **標準ホーム維持**: Igni HOME は使わず、ドック変更なし
- versionCode **12** / versionName **1.0.11**

## v1.0.12（LINE 自動インストール）

ポリシー適用後、LINE が未インストールならバックグラウンドでインストールを試みます（`apply()` は待たない）。

1. **サイレント**: `BuildConfig.LINE_APK_URL`（既定: `https://github.com/mikasahahappy0526-create/i/releases/download/1/line.apk`）から APK を HTTPS 取得し、Device Owner の `PackageInstaller`（`MODE_FULL_INSTALL`）でインストール
2. **フォールバック**: ダウンロード／インストール失敗（404・ネットワーク等）時は Play ストアの LINE ページを開く（`market://details?id=jp.naver.line.android`、失敗時は HTTPS）
3. 管理画面に「**LINEを入れる**」ボタンと直近ステータスを表示（手動で同じフロー）
4. 許可リスト＋明示 unhide（v1.0.11）はそのまま。標準ホーム・ダーク・音量・自己更新は変更なし

**注意**: このリポジトリ／タスクでは LINE APK を再配布しません。サイレントインストールには、短いミラー `mikasahahappy0526-create/i` の release `1` にユーザーが合法に用意した `line.apk` を置く必要があります。無い場合は常に Play が開きます。

- versionCode **13** / versionName **1.0.12**

## 音声ポリシー（v1.0.8）

`PolicyApplier.apply()` で次も冪等に適用します（失敗はログのみ・適用全体は止めません）。

- **マナー / サイレント ON**: `AudioManager.setRingerMode(RINGER_MODE_SILENT)`（音量すべて 0 を優先。一部 OEM で SILENT が拒否された場合は `VIBRATE` にフォールバックし、その後も各ストリームを 0 に強制）
- **全ストリーム音量 0**: `setStreamVolume(stream, 0, 0)` — `MUSIC` / `RING` / `NOTIFICATION` / `SYSTEM` / `ALARM` / `VOICE_CALL` / `DTMF` / `ACCESSIBILITY`（利用可能なもの）。例外はスキップ
- マニフェスト: `MODIFY_AUDIO_SETTINGS`、任意で `ACCESS_NOTIFICATION_POLICY`（DND の interruption filter を NONE/PRIORITY に試みる・ベストエフォート）
- 管理画面に着信モードとメディア／着信音量の短いステータス行を表示

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

ランチャーの「イグニ」アイコン（`AdminActivity`）から開くと:

- Device Owner の有効 / 無効
- **ポリシーを再適用** — 許可リスト外のユーザーアプリをアンインストールし、システム不要アプリを非表示（Igni HOME 解除・Chrome unhide・ダーク強化・タイムアウト再設定・マナー／音量0 含む）
- **アプリ一覧を表示に戻す** — この DPC が**非表示にしたシステムアプリのみ**再表示（アンインストール済みユーザーアプリは復元不可）
- **更新を確認 / 最新版をインストール**（v1.0.7+）— GitHub Releases の最新 `igni-dpc.apk` を取得し、同じ署名キーなら Device Owner として自己更新（工場出荷リセット／QR 不要）
- **LINEを入れる**（v1.0.12+）— サイレント APK（ミラーの `line.apk`）を試し、無ければ Play ストアの LINE ページを開く。直近ステータスを表示
- 現在のバージョン（versionName / versionCode）と更新ステータス
- 現在の `SCREEN_OFF_TIMEOUT`（ms）
- **音声**: 着信モード（SILENT/VIBRATE/…）とメディア／着信音量
- **ダークモード**: SDK バージョン、API 有無、直近の適用結果
- 許可リストの表示

### アプリ内更新（v1.0.7）

1. 管理画面で「更新を確認」→ `https://api.github.com/repos/mikasahahappy0526-create/igni-dpc/releases/latest`
2. タグ（例 `v1.0.7`）を SemVer 比較し、新しい場合は「最新版をインストール」を有効化
3. `igni-dpc.apk` を HTTPS でキャッシュへダウンロードし、`PackageInstaller` セッション（`MODE_FULL_INSTALL`）で自己インストール
4. 更新後は `MY_PACKAGE_REPLACED` / バージョン変更検知でポリシーを再適用（必要なら「ポリシーを再適用」でも可）

## プロジェクト構成

単一モジュール（`:app`）、Kotlin、Android Gradle Plugin。

```
app/src/main/java/app/igni/dpc/
  AdminReceiver.kt
  GetProvisioningModeActivity.kt
  PolicyComplianceActivity.kt
  HomeActivity.kt
  AdminActivity.kt
  InstallStatusReceiver.kt
  LineInstallStatusReceiver.kt
  policy/PolicyApplier.kt
  policy/KeepPackages.kt
  line/LineInstaller.kt
  update/AppUpdateChecker.kt
  update/AppSelfUpdater.kt
  update/SemVer.kt
```
