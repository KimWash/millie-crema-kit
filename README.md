# millie-crema-kit

단종된 전자책 단말 **크레마 카르타(CREMA-0640L, Android 4.2.2)** 에서 **밀리의 서재**를 다시 읽을 수 있게 하고, 전자잉크에 맞는 홈 화면까지 갖추게 하는 설치 키트입니다.

```
./install.sh            # 대화형 메뉴로 필요한 부분만 골라 설치
```

---

## 문제

| | 상황 |
|---|---|
| 기기 | 크레마 카르타 CREMA-0640L — Rockchip RK3026, RAM 512MB, 758×1024 전자잉크, **Android 4.2.2 (API 17)** |
| 밀리의 서재 | 현재 앱(2.4.0.0)은 **Android 4.4(API 19) 이상**용. 4.2에서는 설치부터 실패하고, 설치돼도 첫 화면에서 죽음 |
| 서버 | 밀리 서버는 **TLS 1.2 + AES-GCM** 만 받음. 4.2는 기본으로 TLS 1.0과 CBC 암호만 씀 → 로그인 불가 |
| 홈 화면 | 기본 홈(YES24 스토어)은 서비스 종료로 사실상 죽은 화면. 대체 런처들은 4.2에서 안 돌거나 전자잉크에 안 맞음 |
| 성능 | 메모리 512MB. 밀리(약 100MB)와 KOReader(약 80MB)가 함께 올라오면 여유가 거의 없음 |

## 무엇을 했나

세 부분으로 나눠 해결했습니다. 각 폴더에 따로 README가 있습니다.

| # | 폴더 | 무엇이 문제였나 | 무엇을 했나 |
|---|---|---|---|
| 1 | [`millie-compat/`](millie-compat/) **밀리 살리기** | 4.2에 없는 API, 낡은 TLS, 네이티브 보안 모듈 충돌, 웹뷰 뷰어 미동작 | 안드로이드 **시스템(framework.jar, core.jar)을 고쳐서** 밀리를 원본 그대로 실행. API 18/19 기능 역이식, TLS 1.2+GCM, 웹뷰 `evaluateJavascript` 브리지, PDF·검색용 네이티브 shim, 성능 조정 |
| 1-1 | [`millie-launcher/`](millie-launcher/) **밀리 래퍼** | 밀리 시작 화면(스플래시)이 4.2에서 멈춤. Wi-Fi가 "연결됨"인데 실제로는 안 될 때 자동 로그인이 한 번 실패하면 끝. 꺼진 앱을 다시 열면 죽은 책 화면이 되살아남 | 스플래시를 건너뛰고 로그인 화면으로 바로 여는 작은 앱 ("밀리의 서재" 아이콘). 인터넷이 진짜 될 때까지 기다리고, 꺼진 밀리는 항상 깨끗하게 새로 시작 |
| 2 | [`crema-home/`](crema-home/) **새 홈 런처** | 쓸 만한 홈 화면이 없음. 기기가 매우 느림 | 신문 지면 같은 전자잉크 전용 홈 "크레마 홈". KOReader 이어 읽기·서재, 밝기·Wi-Fi 조절, 홈 버튼 두 번으로 실행 중인 앱 관리. 폰트 서브셋·단일 화면 그리기 등 저사양 최적화 |

## 결과

- 로그인, 내 서재, 책 내려받기, **EPUB·PDF 읽기**, 검색까지 원본 밀리 앱으로 동작합니다.
- 밀리 APK는 **수정하지 않습니다**(리패키징 없음). 바꾸는 것은 기기 시스템뿐입니다.
- 크레마 홈: APK 약 1.36MB, 실행 중 메모리 약 8MB, 첫 실행 약 0.4초.
- 기기 정리(애니메이션 끄기, 죽은 기본 앱 비활성화) 뒤 여유 메모리 13MB → 약 50MB.

## 지키는 원칙

- **DRM이나 구독을 우회하지 않습니다.** 본인 계정으로 본인이 산 책을 읽기 위한 호환성 작업입니다. 책 복호화는 밀리 앱의 DRM 모듈이 그대로 합니다.
- **밀리의 그림·로고는 이 저장소에 없습니다.** 런처 로고는 사용자가 가진 원본 APK에서 빌드할 때 뽑아 쓰고(`--apk`), 크레마 홈은 설치된 밀리 앱의 아이콘을 실행 중에 읽어 씁니다.
- 시스템을 바꾸는 모든 단계는 기기에 **되돌리기 스크립트**를 남깁니다.

## 설치

필요한 것:
- **루팅된** CREMA-0640L, USB 디버깅 연결 (`adb devices`에 보일 것)
- macOS/Linux, JDK 8 이상, Android SDK(build-tools, platforms 아무 버전)
- 밀리의 서재 2.4.0.0 원본 APK (로고 추출 및 설치용, 선택)

```
./install.sh                       # 대화형
./install.sh --all --apk Millie-2.4.0.0.apk --install-millie
./install.sh --crema-home          # 홈 런처만
./install.sh --dry-run ...         # 기기는 건드리지 않고 무엇을 할지만 출력
```

| 옵션 | 내용 | 폴더 |
|---|---|---|
| `--root` | su + SuperSU 설치(처음 한 번) | — |
| `--framework` | 패치한 framework.jar (대부분의 부분이 필요로 함) | millie-compat |
| `--core` | 패치한 core.jar (TLS 1.2 + GCM, SHA-256 서명) | millie-compat |
| `--system` | 저장소 권한 그룹, 밀리 DRM 네이티브 라이브러리 | millie-compat |
| `--wrap` | 밀리 전용 실행 래퍼(PDF·TLS·힙 shim) | millie-compat |
| `--webcompat` | EPUB 뷰어 호환 설정 | millie-compat |
| `--browser-tls` | 기본 브라우저도 TLS 1.2 | millie-compat |
| `--launcher` | "밀리의 서재" 래퍼 앱 | millie-launcher |
| `--crema-home` | 크레마 홈 런처 | crema-home |
| `--debloat` | 죽은 YES24/CREMA 기본 앱 비활성화(되돌릴 수 있음) | — |

되돌리기: 설치 때마다 기기의 `/sdcard/millie-crema-backup/<시각>/restore.sh` 가 만들어집니다.
```
adb shell su -c "sh /sdcard/millie-crema-backup/<시각>/restore.sh" && adb reboot
```

> **주의:** Android 4.2에서 `com.android.location.fused` 를 비활성화하면 system_server가 죽어 **부트루프**가 납니다. `--debloat` 목록에는 넣지 않았고, 손으로도 끄지 마세요.

## 남은 문제

- 책을 연 직후 밀리 앱 내부 JSON 처리로 30~45초 버벅임 (앱 코드라 시스템 쪽에서 줄이기 어려움)
- PDF 색의 빨강/파랑이 바뀌어 보임(흑백 화면에서는 거의 티 안 남)
- EPUB을 본 뒤 PDF를 열면 메모리 부족으로 앱이 내려갈 때가 있음
- 아이폰 핫스팟은 기기가 잠에서 깬 뒤 데이터가 안 흐르는 경우가 있음(밀리 래퍼가 기다렸다가 안내)

## 폴더 구조

```
install.sh              설치기 (부분 선택, 되돌리기 스크립트 생성)
scripts/common.sh       공용 함수 (SDK 찾기, smali 도구 받기)
millie-compat/          1. 밀리 살리기 — 시스템 패치, 네이티브 shim, 웹뷰 호환 설정, 분석 도구
millie-launcher/        1-1. 밀리 래퍼 앱
crema-home/             2. 크레마 홈 런처
```
