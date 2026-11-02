# millie-compat — 밀리 살리기

Android 4.2.2(API 17) 크레마 카르타에서 **원본 밀리의 서재 앱(2.4.0.0, minSdk 19)** 을 그대로 실행하기 위한 시스템 쪽 수정입니다. 앱 APK는 건드리지 않고, 기기의 `framework.jar` / `core.jar` 와 시스템 설정 파일만 바꿉니다.

## 왜 앱이 아니라 시스템을 고쳤나

밀리 APK는 AppGuard로 보호되어 있어서 실제 코드가 암호화된 상태로 실행 중에 풀립니다. APK를 고쳐 다시 패키징하는 길은 보안 모듈과 DRM 때문에 막혀 있고, 그것을 뚫는 것은 이 작업의 범위가 아닙니다. 대신 앱이 기대하는 **Android 4.4의 동작을 4.2 시스템에 채워 넣는** 방식으로 갔습니다.

## 문제 → 해결

막히는 지점을 하나 풀면 다음 지점이 나오는 식이었습니다. 아래는 실제로 부딪힌 순서입니다.

### 1. 설치와 실행 시작
| 문제 | 해결 | 위치 |
|---|---|---|
| APK가 SHA-256으로 서명돼 4.2가 검증 못 함 | `JarVerifier`/`JarUtils`에 SHA-256 추가 | core.diff |
| minSdk 19라서 설치 거부 | 패키지 파서의 minSdk 검사 통과 | framework.diff `PackageManager` |
| 보안 모듈(`libloader.so`)이 백그라운드 스레드에서 `ActivityThread.currentActivityThread()`를 부르고, 4.2는 거기서 null을 돌려줘 **네이티브 크래시** | 앱 uid(≥10000)에 한해서만 전역 `sCurrentActivityThread`로 대체. 시스템 uid는 기존 동작 유지(기본 홈이 null을 기대해서) | `ActivityThread`, `Instrumentation` |

### 2. Android 4.4 API 빈자리
밀리는 minSdk 19라서 코드 안의 버전 검사가 모두 지워져 있습니다. 4.4에 처음 생긴 클래스·메서드를 부르면 바로 죽습니다. 실행해 보며 하나씩 채웠습니다.

- 클래스: `java.util.Objects`, `android.util.ArrayMap`
- 메서드: `View.isLaidOut/isAttachedToWindow/cancelPendingInputEvents`, `Bitmap.getAllocationByteCount`, `StatFs.get*Long`, `ActivityManager.isLowRamDevice`, `HandlerThread.quitSafely`, `Drawable.setAutoMirrored`, `ObjectAnimator.setAutoCancel` 등
- `Bitmap.reconfigure`는 **일부러 비워 두지 않았습니다.** 가짜로 채우면 Glide 이미지 크기가 깨집니다. 대신 Glide 비트맵 재사용을 끕니다(`webcompat/*.setstatic`).

빠진 API를 찾는 도구: `tools/api_gap_scan.py`(앱 smali에서 4.2에 없는 참조 찾기), `tools/api_gap_filter.py`(API 레벨로 거르기), `tools/api19_backport.py`(반복 적용).

### 3. 저장소와 DRM
| 문제 | 해결 | 위치 |
|---|---|---|
| 앱이 외장 저장소 쓰기 권한 없이 자기 폴더에 쓰려 함(4.4 동작) | `READ_EXTERNAL_STORAGE`에 쓰기 그룹도 부여 | `--system` (platform.xml) |
| DRM 모듈의 의존 라이브러리를 4.2 로더가 앱 폴더에서 못 찾음 | `libPocoFoundation/libPocoZip/libsqlcipher.so`를 `/system/lib`에 복사(밀리 설치 후) | `--system` |

### 4. 네트워크
| 문제 | 해결 | 위치 |
|---|---|---|
| 서버가 TLS 1.2만 받음 | Java 기본 프로토콜에 TLS 1.1/1.2 추가 | core.diff `NativeCrypto` |
| 서버가 AES-GCM 암호만 받음. 4.2 네이티브 OpenSSL에는 있는데 Java가 안 내보냄 | GCM 암호 묶음을 Java 쪽 목록에 등록 | core.diff `NativeCrypto` |
| 검색 화면(웹뷰)은 Chromium 네트워크 스택이라 위 수정이 안 먹음 | 밀리 프로세스에만 `libcompat_webtls.so` 미리 로드(TLS 1.1/1.2 막는 옵션 해제) | `native/webtls_shim`, `--wrap` |

### 5. EPUB 뷰어 (웹뷰)
EPUB 뷰어는 epub.js 웹 페이지이고, 앱이 `WebView.evaluateJavascript`(4.4)로 결과값을 받아 갑니다. 4.2 웹뷰에는 이 메서드가 없습니다.

- **EvalBridge**: `addJavascriptInterface`로 결과를 돌려받는 방식으로 `evaluateJavascript`를 구현 (`bridge/src/android/webkit/EvalBridge.java`)
- **WebCompat**: `/system/etc/webview_compat/<패키지>.*` 파일이 있을 때만 켜지는 앱별 호환 계층 (`bridge/src/android/webkit/WebCompat.java`)
  - `.js` 페이지 맨 앞에 넣는 폴리필(classList, requestAnimationFrame, Event 생성자 등)
  - `.sub` 찾아 바꾸기(CSS `inline-flex` 등)
  - `.dedupe` 한 번 탭에 페이지가 두 번 넘어가는 문제 방지
  - `.swlayer` 하드웨어 레이어 대신 소프트웨어 레이어(iframe 깨짐 방지)
  - `.unleak` 닫힌 화면을 붙잡고 있는 스레드의 참조를 풀어 메모리 누수 방지
  - `.warm` 뷰어가 열릴 때 Kotlin 리플렉션을 미리 데워 첫 페이지 시간을 줄임
- 웹뷰 종료 후 접근 크래시 방지 가드(`WebViewClassic`)

### 6. PDF
| 문제 | 해결 |
|---|---|
| PDF 라이브러리가 `ArrayMap`(4.4) 사용 | framework에 `ArrayMap` 역이식 |
| 앱의 `libc++_shared.so`가 4.2 libc에 없는 `statvfs` 심볼을 요구 | `libcompat_statvfs.so`(시스템 콜로 구현)를 밀리에만 미리 로드 |

### 7. 성능·안정성
- `millie_wrap.sh`: 밀리만 래퍼로 실행(`build.prop`의 `wrap.kr.co.millie.eink`). shim 미리 로드, `-Xverify:none`
- `libcompat_props.so` + `millie_props`: 시스템 속성 값을 밀리에게만 다르게 보여 힙 크기 조정
- 숨겨진 GIF가 계속 돌던 문제: `ImageView` 가시성 처리 역이식
- 크레마 기본 홈 등 시스템 앱은 영향받지 않게, 대부분의 수정은 앱 uid나 패키지 이름으로 범위를 좁혔습니다.

## 파일

```
patches/framework.diff   framework.jar smali 패치 (v38, 52개 클래스)
patches/core.diff        core.jar smali 패치 (서명 검증, TLS/GCM)
bridge/                  EvalBridge, WebCompat, ArrayMap, AppWarmup 원본 Java (패치의 출처)
native/*_shim/           LD_PRELOAD shim 소스와 빌드된 .so (statvfs, webtls, props)
webcompat/               /system/etc/webview_compat/ 에 들어가는 밀리 전용 설정
tools/                   분석·측정 스크립트, smali/baksmali
build_jar.sh             원본 jar + diff → 패치된 jar
build_native.sh          shim 다시 빌드 (NDK 필요)
```

## 빌드와 설치

보통은 최상위 `install.sh`가 다 해 줍니다(`--framework --core --system --wrap --webcompat`).

직접 jar만 만들 때:
```
millie-compat/build_jar.sh stock-framework.jar millie-compat/patches/framework.diff framework.jar
```
- 입력 jar는 **패치 전 원본**이어야 합니다. 설치기는 처음 실행 때 기기 원본을 `/sdcard/millie-crema-backup/stock/`에 보관합니다.
- 프레임워크를 바꾼 뒤 첫 부팅은 모든 앱을 다시 최적화하느라 몇 분 걸립니다.

## De-odex (사전 조건)

크레마 기본 펌웨어는 시스템 jar와 앱이 미리 최적화(odex)된 상태입니다. 이 상태에서 framework 클래스에 메서드를 추가하면, 미리 최적화된 다른 시스템 파일이 옛 메서드 위치를 가리켜 **부팅이 반복됩니다**. 그래서 기기 전체를 de-odex(모든 jar/apk에 dex를 넣고 odex 삭제)한 뒤 적용합니다. 그러면 부팅 때 기기가 패치된 framework에 맞춰 전부 다시 최적화합니다. `build_jar.sh`가 "no classes.dex" 오류를 내면 아직 de-odex가 안 된 펌웨어입니다.

## 알아 둘 점

- `com.android.location.fused`를 끄면 4.2에서 부트루프가 납니다.
- 밀리 시작 화면(스플래시)은 여전히 멈춥니다. 앱 쪽 장치 ID 확인 과정 때문으로 보이며, [`millie-launcher`](../millie-launcher/)가 이 화면을 건너뜁니다.
- 밀리 서버 IP를 `/etc/hosts`에 고정하는 `--hosts`는 IP가 바뀌므로 권장하지 않습니다(예전 DNS 문제 대응용).
