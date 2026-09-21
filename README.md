# イグニ DPC（Device Policy Controller）

完全管理端末（Device Owner）向けの最小 DPC です。QR プロビジョニングが終わると、**端末標準ホーム（Samsung One UI 等）**のまま、許可リスト外について次を適用します。設定・Play・Chrome・LINE・アライブ・カメラは表示を維持します。

- **許可リスト外**（ユーザー／システム／更新システム共通）: まず Device Owner として `PackageInstaller.uninstall` で**サイレントアンインストールを試行**（容量解放・「個人用に戻す」後も戻らない）
- **FORCE_UNINSTALL**: Google スイート（非 Chrome）＋ Yahoo／Y!mobile／SoftBank／UQ／nubia／PayPay 等を明示＋ヒューリスティックで優先除去
- **フォールバック**: アンインストール失敗／システムスタブが残る場合のみ `setApplicationHidden(true)` で非表示（「個人用に戻す」では許可リスト以外を再表示しない）

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
- この DPC 自身 `app.igni.dpc`（`AdminActivity` が LAUNCHER。`HomeActivity` は無効・HOME にしない）
- **非表示対象**: `com.google.android.googlequicksearchbox` など Google アプリ／検索／Assistant（Chrome の代替にしない）
- SystemUI、IME など端末動作に必要なパッケージ

**隠さない安全リスト（例）**

SystemUI、PackageInstaller、PermissionController、Google Play 開発者サービス、セットアップウィザード、Managed Provisioning、デフォルトランチャー、IME（キーボード）、WebView など。これらを隠したり消したりすると端末が操作不能になるため、対象外です。

Lock Task（キオスク）は **デフォルトオフ** です。有効にする場合は `app/build.gradle.kts` の `ENABLE_LOCK_TASK` を `true` にしてください。

## ホーム画面（v1.0.11: 標準ランチャー）

**Igni を HOME にしない**（Galaxy A23 等でドック／管理ホームに固定されて Chrome が使えなくなる問題の修正）。

- マニフェスト: `HomeActivity` は **無効**（`enabled=false`、HOME/DEFAULT フィルタなし）
- `AdminActivity` のみ `MAIN` + `LAUNCHER`（管理・再適用・更新用）
- `PolicyApplier.apply()` のたび: `clearPackagePersistentPreferredActivities(admin, packageName)` のみ。**`addPersistentPreferredActivity` は呼ばない**
- ホームは Samsung One UI / 端末標準ランチャー。許可リストにより設定・Play・Chrome・LINE・カメラ・イグニがランチャーに残る
- Chrome: 適用時に明示 unhide + http/https 既定ハンドラ候補。Google アプリは force-hide。lock-task 既定オフ。カスタムホームなし
- ホーム1ページ目の自動ピンはベストエフォート（多くの OEM では確認必須 → Admin に正直なステータス）
- LINE: `jp.naver.line.android` を許可リストに追加し、適用時に明示 unhide。未インストール時は適用後に GitHub ミラー XAPK をサイレントインストール（v1.0.35；自動では Play を開かない）

## カメラ保護（v1.0.4）

一部 OEM ではカメラのパッケージ名が静的リストに無く、hide ポリシー適用後にホームからカメラが消えることがありました。v1.0.4 では:

- `KeepPackages.detectCameraPackages()` が次を **動的** に keep 対象にします
  - `MediaStore.ACTION_IMAGE_CAPTURE` / `android.media.action.IMAGE_CAPTURE` / `STILL_IMAGE_CAMERA` / `VIDEO_CAMERA` を解決するパッケージ
  - MAIN/LAUNCHER があり packageName に `camera` を含むパッケージ（大文字小文字不問）
  - 既存の静的リスト（Sony / Xiaomi / Transsion / Sharp / FCNT / Kyocera 等を拡充）
- `PolicyApplier.apply()` のたびに検出したカメラを **明示的に unhide** し、`HiddenStore` からも除去
- 管理画面（`AdminActivity`）に検出カメラパッケージ一覧を表示

## アンインストール vs 非表示（v1.0.5 → **v1.0.31 でアンインストール優先**）

`PolicyApplier.apply()` は許可リスト外のパッケージを次のように扱います。

| 種別 | 判定 | 動作 |
|---|---|---|
| 許可リスト外（すべて） | `shouldKeep` / hard-deny / Chrome / カメラ / trichrome / 自己以外 | **まずサイレントアンインストール**（`PackageInstaller.uninstall`）。更新システムも試行（更新削除／アプリ削除になりうる） |
| フォールバック | アンインストール未提出、またはシステム／更新システムでスタブ残存、かつ起動可能 | **非表示**（`setApplicationHidden(true)`）のみ |

- 許可リスト・DPC 自身・IME・SystemUI 等（`KeepPackages.shouldKeep` / CRITICAL）は絶対に消さない／隠さない（対象集合は従来の「隠していた集合」と同じ）
- Google アプリ強制除去・TikTok Lite も同じ「アンインストール優先 → 非表示フォールバック」
- アンインストールしたパッケージは `HiddenStore` から除去。非表示フォールバックのみ追跡
- 「個人用に戻す」は **許可リスト／CRITICAL のみ**再表示（`unhideOnlyKeepPackages`）。FORCE_UNINSTALL／非 keep の非表示は戻さない。アンインストール済みも戻らない
- ログ: `uninstallRequested`（試行数）と `hideFallback`（非表示フォールバック数）

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
- **自動回転 OFF（v1.0.41）**:
  - `Settings.System.putInt(..., ACCELEROMETER_ROTATION, 0)` のあと **読み戻してログ**
  - 利用可能なら反射で `DevicePolicyManager.setSystemSetting(admin, ACCELEROMETER_ROTATION, "0")` も試す
  - `USER_ROTATION` は変更しない（固定向きは端末側のまま）。失敗はログのみ

## Samsung One UI ダークモード（v1.0.9）

Galaxy A23 など One UI では標準の `UiModeManager` / `ui_night_mode` だけではダークが効かないことがあります。v1.0.9 の `applyDarkMode()` は次を追加で試します（いずれもベストエフォート・例外は握りつぶし）。

1. 既存: `UiModeManager` + `Settings.Secure.ui_night_mode=2`
2. Samsung クラシック: `Settings.System.putInt(cr, "display_night_theme", 1)`
3. 既知の安全キー: `dark_theme` / `theme_mode` / `night_mode`（System/Secure、1 または 2 = ON）
4. 反射 `DPM.setSystemSetting` / `setSecureSetting` で `ui_night_mode` と `display_night_theme`
5. 書き込み後: `setApplicationNightMode`（あれば）＋ Samsung / night 系ブロードキャスト
6. 管理画面: 適用後の `display_night_theme` 読み戻しが 1 かどうかを表示

v1.0.8 のマナーモード＋音量 0、更新ボタン、アンインストールは維持しています。

## v1.0.44（アライブ 0.1.82 固定配布）

- **AliveInstaller**: 公開アライブ **0.1.82**（versionCode 83）の GitHub Releases APK を一次 URL に固定。`latest/download` はフォールバック
- ダウンロード後に SHA-256（`646bd751c434627d8c7d05981156bcf4f6fb6ddde62c37d3cbf0d9f798aa3af6`）を検証し、不一致は日本語ステータス「ハッシュ不一致（中止）」でハード失敗
- KeepPackages のパッケージ `jp.puchicli.app` は変更なし
- versionCode **45** / versionName **1.0.44**

## v1.0.43（アライブ 0.1.81 固定配布）

- **AliveInstaller**: 公開アライブ **0.1.81**（versionCode 82）の GitHub Releases APK を一次 URL に固定。`latest/download` はフォールバック
- ダウンロード後に SHA-256（`f61b35450a78110e4cfd877b56bdb7df4caca96ea4983f538821db1cefb21fda`）を検証し、不一致は日本語ステータス「ハッシュ不一致（中止）」でハード失敗
- KeepPackages のパッケージ `jp.puchicli.app` は変更なし
- versionCode **44** / versionName **1.0.43**

## v1.0.42（アライブ 0.1.80 固定配布）

- **AliveInstaller**: 公開アライブ **0.1.80**（versionCode 81）の GitHub Releases APK を一次 URL に固定。`latest/download` はフォールバック
- ダウンロード後に SHA-256（`cd153617fd28aa3a98a26fa8ae95f186c5eab782b9ce3596a11f5f4369cdf6e6`）を検証し、不一致は日本語ステータス「ハッシュ不一致（中止）」でハード失敗
- 既存アライブが署名違い／更新不可のときは「要アンインストール（署名/旧版）」を表示（サイレントアンインストールはしない）
- KeepPackages の `jp.puchicli.app` は変更なし
- versionCode **43** / versionName **1.0.42**

## v1.0.41（自動回転OFF）

- **自動回転 OFF**: `PolicyApplier.apply()` でベストエフォート `applyAutoRotateOff()` — `Settings.System.ACCELEROMETER_ROTATION=0`（put + 読み戻し）、反射 `DPM.setSystemSetting`、代替キーも試行。`USER_ROTATION` は触らない。OEM 拒否はログのみで適用は継続
- versionCode **42** / versionName **1.0.41**

## v1.0.40（LINE成功後に自動で個人用へ＋緊急速報メールOFF）

- **LINE 成功後の自動「個人用に戻す」**: Device Owner 中に `PackageInstaller` が LINE を **実インストール成功**（`LineInstallStatusReceiver` → `persistSuccess`）したときだけ、既存の `PolicyApplier.returnToPersonalUse` を自動実行。既に LINE が入っていた no-op では動かない。管理画面の LINE ボタン成功時も同様。個人用モードではクリアしない。ワンショットフラグで再入防止。Alive がインストール中なら最大約 90 秒待ち、済み／不要ならすぐクリア
- **緊急速報メール OFF**: `apply()` でベストエフォート `applyEmergencyAlertsOff()`（Settings / DPM / SemSettings の候補キーに 0、既知の cell-broadcast／キャリア緊急メールパッケージを hide／disable。SMS・電話は触らない）
- versionCode **41** / versionName **1.0.40**（※ v1.0.39 が既に versionCode 40 のため繰り上げ）

## v1.0.39（アライブ見出しの重複を解消）

- 管理画面の「アライブ」見出しを削除し、LINE と同じ `[アライブ][開く]` ボタン行だけに整理
- versionCode **40** / versionName **1.0.39**

## v1.0.38（LINEアカウント外し UI の削除）

- 管理画面から「LINEアカウントを外す」ボタン、確認ダイアログ、関連するアプリデータ消去ヘルパー／文字列を削除
- LINE／アライブの個人用モードでのインストール、Device Owner 時の自動導入、機内モード削除など v1.0.37 のその他の動作は維持
- versionCode **39** / versionName **1.0.38**

## v1.0.37（個人用モードで LINE／アライブ継続・機内モード削除）

- **個人用モード（Device Owner 解除後）**: Admin の LINE／アライブ インストールが通常アプリとして動作。DO 時は従来どおりサイレント `PackageInstaller`。非 DO 時はダウンロード後にユーザー確認付きセッション（または単一 APK の `FileProvider` + `ACTION_VIEW`）。`REQUEST_INSTALL_PACKAGES` ＋必要時に提供元不明の許可画面
- **自動インストール**: `PolicyApplier.apply()` からの LINE／アライブ自動導入は **DO のみ**（従来どおり）
- **機内モード削除**: Admin スイッチ・`AirplaneModeHelper`・関連文字列／`WRITE_SECURE_SETTINGS`（機内専用）を削除
- **個人用に戻す後**: Admin は起動可能なまま。LINE／アライブのインストールを継続利用可能

## v1.0.36（充電情報を表示 OFF）

モーリー氏報告: Galaxy A23 / Sense 系でロック画面の「充電情報を表示」（充電中の残量％・満充電までの時間）をセットアップ時にオフにしたい。

- **PolicyApplier.apply()**（再適用含む）でベストエフォート `applyChargingInfoOff()`:
  - 候補キーに `0` を書込: `show_charging_info` / `sec_show_charging_info` / `lock_screen_show_charging_info` / `charging_info` / `display_charging_info` / `charging_information` ほか Samsung / Sense 系バリアント
  - `Settings.System` / `Secure` / `Global` の `putInt`、反射 `DPM.setSystemSetting` / `setSecureSetting` / `setGlobalSetting`、Samsung `SemSettings.System.putInt`
  - **触らない**: `show_battery_percent`（ステータスバー電池％とは別）
  - 成功したキーをログ。存在しなくてもクラッシュしない
- Admin 専用 UI は不要（declutter 維持）。ダークモードは v1.0.34 で撤去済みのまま
- 維持: LINE GitHub XAPK、Alive 自動、FORCE_UNINSTALL、機内、ja_JP、Chrome 保護
- versionCode **37** / versionName **1.0.36**

## v1.0.35（LINE を GitHub XAPK 直DLに変更）

- **LineInstaller**: Uptodown 解決をやめ、ミラー `https://github.com/mikasahahappy0526-create/i/releases/download/line/line.xapk` から HTTPS（リダイレクト追従・User-Agent）で XAPK を取得。既存の XAPK 展開＋分割 APK `PackageInstaller` はそのまま
- 自動パスは引き続き Play を開かない。管理画面の LINE / Play ボタンは維持。ステータスは日本語のみ
- LINE 資産: GitHub Releases タグ `line` / 資産名 `line.xapk`（26.11.0）
- versionCode **36** / versionName **1.0.35**

## v1.0.34（ダークモード機能を完全削除）

モーリー氏報告: ダークモードが依然として効かないため、機能自体を撤去。

- **Admin UI**: 「ダークモード」ボタンおよびダーク関連ステータス表示／専用 strings を削除
- **PolicyApplier / BootReceiver**: `DarkModeHelper` / `applyDarkMode` 呼び出しと永続化・refresh コードを削除（画面タイムアウト等の他ポリシーは維持）
- **削除**: `DarkModeHelper.kt`、`MODIFY_DAY_NIGHT_MODE` 権限、`DarkModeStatus`
- 維持: FORCE_UNINSTALL / 個人用に戻す keep-only unhide、機内、アライブ自動、LINE（Uptodown）、Chrome 保護、ja_JP、Admin UI
- versionCode **35** / versionName **1.0.34**

## v1.0.33（ダークモード強制強化＋Admin「ダークモード」設定ボタン）

モーリー氏報告: ダークモード自動切替がまだ効かない端末向け。

- **DarkModeHelper 強化**: car-mode poke を2回＋待機延長、追加 OEM キー（`dark_mode_state` / `ui_night_mode_override` 等）、`settings put` shell、追加ブロードキャスト、binder `setNightModeActivated`。既存の grants / display_night_theme / ui_night_mode / cmd uimode / SEM・Knox 反射は維持
- **正直な成功判定**: `result=success` はプローブ（`display_night_theme==1` / `ui_night_mode==2` / `nightMode==YES` / `UI_MODE_NIGHT_YES`）が ON のときのみ。書込だけ成功しても fail
- **Boot / ポリシー適用**: 従来どおり `PolicyApplier.apply()` 経由で再適用（BootReceiver 含む）
- **Admin 小ボタン「ダークモード」**: 機内モード付近。押下で強制再適用 → まだ暗い場合は Samsung One UI ダーク設定 Activity を PackageManager で解決して起動（`Settings$DarkModeSettingsActivity` 等）→ 失敗時は `ACTION_DISPLAY_SETTINGS` → `ACTION_SETTINGS`。開けなければトースト
- 維持: FORCE_UNINSTALL / 個人用に戻す keep-only unhide、機内、アライブ自動、Chrome 保護、ja_JP、Admin UI declutter
- versionCode **34** / versionName **1.0.33**

## v1.0.32（FORCE_UNINSTALL 強化・個人用に戻すでブロート再表示しない）

モーリー氏 Y!mobile nubia 報告: 「個人用に戻す」後に、非表示だけだった Google スイート／Yahoo／キャリア系がランチャーに戻る問題への対応。

- **FORCE_UNINSTALL** セット拡充: Drive / Docs・Sheets・Slides / Maps / Photos / Gmail / YouTube・YT Music / Files by Google / Calendar / Keep / Meet / Podcasts / Wallet / Google TV / News / Duo(Tachyon) / Google Messages（他 SMS があるときのみ）など
- **JP キャリア／Yahoo ヒューリスティック**: パッケージ名に `yahoo` / `ymobile` / `softbank` / `uqmobile` / `anshin` / `kisekae` / `sakusaku` / `paypay` / `oneseg` を含むもの＋明示定数（Y!メール・Y!ブラウザ・My Y!mobile・あんしんフィルター・データ移行・nubia/ZTE 系など）
- **apply()**: FORCE_UNINSTALL も Google／TikTok と同様に **アンインストール優先 → 非表示／無効化フォールバック**（hide-only のまま放置しない）
- **個人用に戻す**: DO 解除前に許可リスト外の最終アンインストールパス → **`unhideOnlyKeepPackages`**（許可リスト／CRITICAL のみ再表示）。強制削除対象の非表示は戻さない
- CRITICAL に AOSP／Samsung SMS（`com.android.mms` / `messaging` 等）を追加。Google Messages は代替 SMS が無い場合は残す
- 維持: Chrome 保護、TikTok Lite 強制削除、アライブ自動インストール、ダークモード、機内モード、ja_JP、Admin UI
- versionCode **33** / versionName **1.0.32**

## v1.0.31（許可リスト外はアンインストール優先）

- **PolicyApplier メインループ**: 許可リスト外はシステム／更新システムも含め **常に `requestSilentUninstall` を先に試行**。失敗またはスタブ残存かつ起動可能なら `setApplicationHidden(true)` をフォールバックのみ
- **狙い**: 「個人用に戻す」後もブロートアプリが復活しない（非表示だけだと unhide で戻る）
- **Google 強制除去**: TikTok Lite と同様にアンインストール優先 → 非表示／無効化フォールバック
- CRITICAL / `shouldKeep` / Chrome / Settings / Play / LINE / Alive / カメラ / trichrome / 自己は従来どおり保護（対象集合は広げない）
- ログ: `uninstallRequested` vs `hideFallback` を記録
- 維持: ダークモード 1.0.30、機内モード、アライブ自動インストール、TikTok 削除、Chrome 保護、ja_JP、Admin UI
- versionCode **32** / versionName **1.0.31**

## v1.0.30（ダークモード強制強化・car mode poke）

- **ダークモード強制**: `DarkModeHelper` を新設し `PolicyApplier.applyDarkMode()` から委譲。設定書込だけでは One UI が無視する問題への対策
  1. `DPM.setPermissionGrantState` で `MODIFY_DAY_NIGHT_MODE` / `WRITE_SECURE_SETTINGS` / `WRITE_SETTINGS` を自己付与
  2. `Settings.System display_night_theme=1` + `Settings.Secure ui_night_mode=2` + OEM キー + 反射 DPM setSystem/SecureSetting
  3. `UiModeManager.setNightMode(MODE_NIGHT_YES)` → API 30+ `setNightModeActivated(true)`
  4. **Tasker/Samsung 技**: `enableCarMode` → 短待機 → `disableCarMode` のあと night を再設定（One UI がダークを実適用）
  5. `Runtime.exec(cmd uimode night yes)` ベストエフォート
  6. 反射 `IUiModeManager` binder `setNightMode`
  7. Samsung `SemUiModeManager` / Knox Custom SettingsManager をクラスがあれば反射（SDK 非依存）
  8. 成功判定: `display_night_theme==1` OR `ui_night_mode==2` OR `nightMode==YES` OR `UI_MODE_NIGHT_YES`。全プローブをログ
  - API &lt; 29（sense3 等）はベストエフォート・クラッシュしない
- 維持: 機内モード多経路、アライブ自動インストール、TikTok Lite 強制削除、Chrome 保護、ja_JP / Asia/Tokyo、Admin UI
- versionCode **31** / versionName **1.0.30**

## v1.0.29（機内モード多経路・アライブ自動インストール）

- **機内モード（多経路）**: `AirplaneModeHelper` を全面書き換え。各経路をログし、成功は `Settings.Global.AIRPLANE_MODE_ON` 読戻し一致のみ
  1. `DPM.setPermissionGrantState(WRITE_SECURE_SETTINGS)` + `DPM.setGlobalSetting` / `Settings.Global.putInt`
  2. 反射 `ConnectivityManager.setAirplaneMode`
  3. Binder `ServiceManager` → `IConnectivityManager.setAirplaneMode`
  4. Samsung / One UI: Knox Custom `SettingsManager.setFlightModeState` / SEM 系（クラスがあれば反射・Knox SDK 非依存）
  5. 全アクティブ回線の `TelephonyManager` / `ITelephony.setRadioPower`（部分機内・ベストエフォート）
  6. `ACTION_AIRPLANE_MODE_CHANGED` を `sendBroadcastAsUser(ALL)`
  7. 全滅時は成功扱いにせず、設定画面 `ACTION_AIRPLANE_MODE_SETTINGS` を開き日本語トースト「この端末では自動切替できないため設定画面を開きました」。Admin に1行ステータス
  - **制限の正直な注記**: 多くの OEM では DO でも `NETWORK_SETTINGS` が無く `setAirplaneMode` が SecurityException になり、Global ビットだけ変わって電波が残ることがある
- **アライブ自動インストール**: `PolicyApplier.apply()` 後に `AliveInstaller.ensureAliveInstalledAsync`（LINE/Chrome と同様）。既インストールはスキップ。GitHub `puchicli.apk` + PackageInstaller（Play は開かない）。`jp.puchicli.app` は PRODUCT_ALLOWLIST + unhide/enable。Admin「アライブ」は手動再試行用
- 維持: Chrome 保護、TikTok Lite 強制削除、ja_JP QR、ホームピン無し、LINE UI、機内スイッチ UI
- versionCode **30** / versionName **1.0.29**

## v1.0.28（TikTok Lite 強制削除・機内モード実効化）

- **TikTok Lite**: `com.zhiliaoapp.musically.go` / `com.tiktok.lite.go` を PRODUCT_ALLOWLIST / HARD_DENY_UNINSTALL から除外。`PolicyApplier.apply()` でサイレントアンインストールを優先し、システム／更新システムで失敗時は `setApplicationHidden(true)`。keep/unhide ループ対象外。未使用の TikTok Lite インストーラ／レシーバを削除
- **機内モード**: `AirplaneModeHelper` を強化。第一候補は反射 `ConnectivityManager.setAirplaneMode(boolean)`（@SystemApi）。続けて DPM.setGlobalSetting / Settings.Global.putInt、ブロードキャスト。最終手段として Telephony/ITelephony `setRadioPower`。成功条件は読戻し `AIRPLANE_MODE_ON` が要求値と一致すること（書込＋broadcast だけでは成功にしない）
- 維持: Chrome 保護、日本語ロケール / Asia/Tokyo、LEAVE_ALL、ホームピン無し、Play 自動オープン無し、LINE / アライブ UI
- versionCode **29** / versionName **1.0.28**

## v1.0.27（機内モード切替・Admin UI整理）

- **Admin UI**: Chrome / TikTokライトのインストール行（ボタン＋Play）を削除。プリインストール Chrome の許可リスト保護は維持。TikTokライトも許可リストは残す
- **機内モード**: 管理画面に「機内モード」MaterialSwitch を追加（更新の下・LINEの上）。Device Owner として `Settings.Global.AIRPLANE_MODE_ON` を切替
  - 優先: 反射 `DevicePolicyManager.setGlobalSetting`
  - フォールバック: `Settings.Global.putInt` + `WRITE_SECURE_SETTINGS`
  - 成功後に `Intent.ACTION_AIRPLANE_MODE_CHANGED`（`state` extra）を `sendBroadcastAsUser`（失敗時は `sendBroadcast`）
  - 失敗時は短い日本語トースト。`onResume` でスイッチ状態を再読込
- 維持: 日本語ロケール / Asia/Tokyo、LEAVE_ALL、ホームピン無し、Play 自動オープン無し、LINE / アライブ横並び
- versionCode **28** / versionName **1.0.27**

## v1.0.26（システム言語を日本語に）

セットアップ中／適用後も端末が英語のまま残る問題への対策。

- **QR**: `PROVISIONING_LOCALE=ja_JP` + `PROVISIONING_TIME_ZONE=Asia/Tokyo` を easy / povo に追加（既存の `LEAVE_ALL_SYSTEM_APPS_ENABLED=true` は維持）
- **PolicyApplier**: DO 確認後にシステムロケール `ja_JP` とタイムゾーン `Asia/Tokyo` をベストエフォートで設定（再適用でも実行 → 既登録の英語端末も工場出荷リセットなしで修正）
  - DPM `setConfiguredLocales`（あれば）→ ActivityManager `updatePersistentConfiguration` / LocalePicker → `DPM.setTimeZone` / `AlarmManager.setTimeZone`
  - `persist.sys.locale` は root 無しでは不可のためスキップ
- Manifest: `CHANGE_CONFIGURATION` / `SET_TIME_ZONE`
- versionCode **27** / versionName **1.0.26**
- Chrome preserve・ホームピン無し・Admin UI（1.0.23–25）は維持。Play は開かない。

## v1.0.25（ホーム画面へのピン留め撤廃）

- **削除**: `HomeLayoutHelper` と `requestPinShortcut` によるホーム画面へのショートカット追加を完全撤去
- **PolicyApplier**: `applyBestEffortHomeLayout` 呼び出しなし。標準ホーム（Samsung One UI 等）のまま
- **Admin**: `homeLayoutStatus` は非表示のまま更新停止
- 維持: 標準ホーム、AdminActivity LAUNCHER（ドロワー可）、Chrome プリインストール保護（v1.0.24）、管理 UI（v1.0.23）、Play 自動オープンなし 等
- versionCode **26** / versionName **1.0.25**

## v1.0.24（プリインストール Chrome 保護）

- **QR**: `PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED=true` を easy/povo ペイロードに追加（プロビジョニング中のシステムアプリ無効化を防止）
- **PolicyApplier**: 非表示／アンインストールループの前に Chrome 系を `setUninstallBlocked` + unhide + enable。プリインストール検出を明示ログ
- **ChromeInstaller**: PackageManager 上に Chrome 系があれば Uptodown 再インストールしない（無効化済みでも enable+unhide のみ）
- **Trichrome**: `com.google.android.trichromelibrary*` を keep / 非アンインストール（Chrome 依存）
- versionCode **25** / versionName **1.0.24**

## v1.0.23（インストール行を横並び）

- 管理画面のインストール操作を縦積みから **1行横並び** に変更: `[アプリ名] [Play]`（アライブは `[アライブ] [開く]`）
- メインボタン height **48dp**（weight=1）、Play/開くは TextButton ~88dp
- ステータス TextView は行の下にコンパクト配置（小さめ top margin・1行）
- 再適用／更新／個人用に戻すも height ~48dp、セクション間の大きな marginTop を縮小
- v1.0.22 ルール維持: アプリ名のみラベル、オレンジ免責は非表示、ステータスは日本語短文のみ
- versionCode **24** / versionName **1.0.23**

## v1.0.22（管理画面 UI ポリッシュ）

- インストールボタン表記をアプリ名のみに短縮（「を入れる」削除）。Play は「Play」、アライブ起動は「開く」
- オレンジ色の免責・注意文（`lineDisclaimer` / `chromeDisclaimer` / `tiktokLiteDisclaimer` / `darkModeNote` / `lockTaskNote`）をすべて非表示
- インストール状態表示を日本語短文のみに（英語ステータスキーや長い詳細テールを出さない）
- versionCode **23** / versionName **1.0.22**

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

## v1.0.13（Uptodown 最新 LINE / XAPK）

ポリシー適用後、LINE が未インストールなら **Uptodown** から最新版を解決してサイレントインストールします（`apply()` は待たない）。ユーザーは第三者リスクを了承済み。

1. **解決**: Uptodown Android eAPI（HMAC → Bearer）で `jp.naver.line.android` の最新 `fileID` を取得し、`dw.uptodown.com` CDN URL を都度解決（固定 URL は使わない）
2. **インストール**: `.apk` は単体、`.xapk` は zip 展開して base+splits を同一 PackageInstaller セッションで投入。OBB はベストエフォートで `Android/obb/jp.naver.line.android/` へコピー
3. **フォールバック**: 解決／DL／インストール失敗時は Play ストア（`market://details?id=jp.naver.line.android`）
4. 管理画面「**LINEを入れる**」は同じフロー。免責: **非公式配布（Uptodown）・改変リスクあり・Playより危険**
5. 許可リスト・標準ホーム・ダーク・音量・自己更新は維持

- versionCode **14** / versionName **1.0.13**

## v1.0.14（Chrome 絶対保護 + 更新ミラー）

**Critical:** ポリシー適用後に Chrome が消える／隠れる問題と、管理画面の更新取得が HTTP 500 で失敗する問題を修正。

### Chrome 絶対保護
1. **Hard deny uninstall**: `PolicyApplier` は `PackageInstaller.uninstall` の前に硬拒否。対象: `com.android.chrome` / `com.chrome.beta` / `com.chrome.dev` / `com.chrome.canary` / Play / Settings / LINE / DPC / `KeepPackages.shouldKeep == true`
2. 適用のたび Chrome 系を **強制 unhide**（LINE/Settings と同じ）し `setUninstallBlocked(true)`
3. Chrome 未インストールなら `ChromeInstaller`（Uptodown 最新 APK/XAPK → Play `market://details?id=com.android.chrome`）を非同期実行
4. 管理画面「**Chromeを入れる**」+ ステータス + Uptodown 免責（LINE と同文）

### 自己更新ダウンロード修正
1. APK 取得は安定ミラー優先: `https://github.com/mikasahappy0526-create/i/releases/download/1/d.apk`
2. GitHub API は User-Agent / `Accept: application/vnd.github+json`、302 を手動フォロー
3. API が 500/403/レート制限なら「ミラーから取得」として更新インストールを許可
4. エラー文言を HTTP コード付きで分かりやすく表示

LINE Uptodown フロー・標準ホーム・ダーク・音量は 1.0.13 のまま維持。

- versionCode **15** / versionName **1.0.14**

## v1.0.16（個人用に戻す / Device Owner 解除）

管理画面に危険操作ボタン **「個人用に戻す（Device Owner解除）」** を追加。確認ダイアログのうえ Device Owner を自己解除します。

1. 非表示にしたシステムアプリを `PolicyApplier.unhideAll()` で再表示
2. `clearPackagePersistentPreferredActivities` / lock-task クリア / 自パッケージの uninstall-blocked 解除
3. `DevicePolicyManager.clearDeviceOwnerApp(packageName)`（deprecated だが DO 自己解除 API）
4. 成功後、管理画面の Device Owner 状態は **いいえ**。イグニは通常アプリになり、以降ポリシーは適用されない
5. アンインストール済みユーザーアプリは復元不可（従来どおり）

Chrome / LINE / 更新 / 標準ホーム / ダーク / 音量ポリシーは変更なし。

- versionCode **17** / versionName **1.0.16**


## v1.0.19（Googleアプリ非表示 + Chrome既定 + Igniを許可リスト）

Sense3 向け: Google アプリが Chrome の代わりに出る問題を解消。標準ホームのまま。

- Google アプリ／検索／Assistant を **強制非表示**（可能ならアンインストール）。Chrome の代替にしない
- 許可リストに **`app.igni.dpc`（イグニ）** を明示。適用時に unhide + enable（`AdminActivity` LAUNCHER）
- Chrome: 適用／インストール後に DPM で http/https の persistent preferred を Chrome に（ベストエフォート）
- ホーム1ページ目へのピンは `ShortcutManager.requestPinShortcut` のベストエフォートのみ。**サイレント保証なし**（OEM確認UIが多い）。カスタム HOME／ドックは再導入しない
- 適用・起動・compliance から Play を自動で開かない（v1.0.18 維持）
- versionCode **20** / versionName **1.0.19**

## v1.0.18（セットアップ中に Play を開かない）


**Critical:** QR / Device Owner セットアップやポリシー再適用中に Google Play ログイン画面へ飛んでホーム到達を妨げる問題を修正。

1. **自動経路では絶対に Play を開かない**: `PolicyApplier.apply()` / BootReceiver / PackageMonitor / compliance / PackageInstaller ステータス受信機 / `ensure*Async` から `openPlayStore` を呼ばない
2. **LINE / Chrome 自動インストールはサイレントのみ**: Uptodown 取得 + `PackageInstaller`。失敗時はログとステータス prefs のみ
3. **管理画面**: 「LINEを入れる」「Chromeを入れる」はサイレント。明示操作の「Playで入れる」ボタンのみ Play を開く
4. **`ensureChromeInstalledPrompt` の Play-first**: apply から削除。Chrome は unhide 済みならそのまま、未導入ならサイレント試行のみ
5. 標準ホーム / DO 解除 / 更新ボタン1つ / ダーク / 音量0 / アンインストール許可リストは維持

- versionCode **19** / versionName **1.0.18**

## v1.0.17（更新ボタン統合）

管理画面の自己更新を **「最新版に更新」** のワンタップフローに統合しました。

1. GitHub API を確認し、失敗時は更新ミラーへフォールバック
2. 最新なら「最新です」と表示して終了
3. 更新があれば直ちにミラー `d.apk` をダウンロードし、`PackageInstaller` で自己更新
4. 確認・ダウンロード・インストールの失敗は日本語ステータスで表示

ポリシー再適用、LINE、Chrome、個人用に戻す、各種ステータス表示は維持します。

- versionCode **18** / versionName **1.0.17**

## v1.0.15（Chrome Play-first + hidden 判定修正）

**Critical:** v1.0.14 でも「ポリシーを再適用」後に Chrome が無く、Play が開かない端末があった問題を修正。

1. **`isChromeInstalled`**: パッケージ有無だけでは足りない。Device Owner 時は DPM `isApplicationHidden` を確認し、hidden なら `setApplicationHidden(false)` で unhide。成功して使える状態になってから true。disabled なら `setApplicationEnabledSetting(ENABLED)` を試行。
2. **Play-first**: Chrome 欠落時は Uptodown より先に **メインルーパーで Play ストア**（`NEW_TASK|CLEAR_TOP|RESET_TASK_IF_NEEDED` + `CATEGORY_BROWSABLE`、優先 `com.android.vending`）を開く。その後バックグラウンドで Uptodown サイレントを任意試行。
3. **`ensureChromeInstalledPrompt`**: `PolicyApplier.apply()` の強制 unhide 後、まだ未インストールならメイン Handler に Play を post（ユーザーが必ず見える経路）。
4. 管理画面「**Chromeを入れる**」: UI スレッドで先に `openPlayStore`、続けてバックグラウンド Uptodown。ステータスは prefs 表示。
5. Hard-deny uninstall（Chrome / Play / Settings / LINE 等）は維持。

- versionCode **16** / versionName **1.0.15**




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
  "android.app.extra.PROVISIONING_LOCALE": "ja_JP",
  "android.app.extra.PROVISIONING_TIME_ZONE": "Asia/Tokyo",
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": false
}
```

`PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED` を `true` にすると、プロビジョニング中にシステムアプリが無効化されず、この DPC が後から非表示制御します。`PROVISIONING_LOCALE` は公式形式 `xx_yy`（例: `ja_JP`）。`PROVISIONING_TIME_ZONE` は IANA（例: `Asia/Tokyo`）。

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

- Device Owner の **はい / いいえ**
- **ポリシーを再適用** — 許可リスト外のユーザーアプリをアンインストールし、システム不要アプリを非表示（Igni HOME 解除・Chrome unhide・ダーク強化・タイムアウト再設定・マナー／音量0 含む）
- **アプリ一覧を表示に戻す** — この DPC が**非表示にしたシステムアプリのみ**再表示（アンインストール済みユーザーアプリは復元不可）
- **個人用に戻す（Device Owner解除）**（v1.0.16）— 確認後に非表示アプリを再表示し Device Owner を解除。イグニは通常アプリになる。アンインストール済みアプリは戻らない
- **最新版に更新**（v1.0.17+）— GitHub API を確認し、更新があればミラー `d.apk` を取得して同じ署名キーで Device Owner として自己更新（工場出荷リセット／QR 不要）
- **LINE / Chrome / TikTokライト / アライブ**（v1.0.23+）— 各インストールは `[アプリ名] [Play]`（アライブは `[アライブ] [開く]`）の1行横並び。ボタンはアプリ名のみ。ステータスは行下の日本語短文のみ。オレンジ色の免責・注意文は非表示
- 現在のバージョン（versionName / versionCode）と更新ステータス
- 現在の `SCREEN_OFF_TIMEOUT`（ms）
- **音声**: 着信モード（SILENT/VIBRATE/…）とメディア／着信音量
- **ダークモード**: SDK バージョン、API 有無、直近の適用結果
- 許可リストの表示

### アプリ内更新（v1.0.17）

1. 管理画面で「最新版に更新」→ `https://api.github.com/repos/mikasahahappy0526-create/igni-dpc/releases/latest`
2. タグ（例 `v1.0.17`）を SemVer 比較し、最新なら「最新です」で終了
3. 新しい場合、または API 失敗時はミラー `https://github.com/mikasahahappy0526-create/i/releases/download/1/d.apk` を直ちにダウンロード
4. `PackageInstaller` セッション（`MODE_FULL_INSTALL`）で自己インストール
5. 更新後は `MY_PACKAGE_REPLACED` / バージョン変更検知でポリシーを再適用（必要なら「ポリシーを再適用」でも可）

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
  chrome/ChromeInstaller.kt
  ChromeInstallStatusReceiver.kt
  update/AppUpdateChecker.kt
  update/AppSelfUpdater.kt
  update/SemVer.kt
```
