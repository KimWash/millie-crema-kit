# millie-launcher

밀리의 서재 실행용 래퍼 앱. 패키지 `kr.co.millie.crema.launcher`, 앱 이름 "밀리의 서재".

## 동작

| 상황 | 처리 |
|---|---|
| 밀리 SplashActivity가 4.2에서 진행되지 않음 | `LoginActivity`를 직접 실행. 저장된 세션이 있으면 앱이 서재로 이동. 스플래시의 초기화는 `Splash` 서비스가 대신 실행 |
| Wi-Fi가 연결 상태지만 데이터가 전달되지 않음. 밀리는 자동 로그인을 1회만 시도 | `apis.millie.co.kr:443` TCP 연결이 될 때까지 대기 후 실행. 30초 초과 시 재시도/그대로 열기 화면 |
| Wi-Fi 꺼짐 또는 10초 내 미연결 | 대기 없이 실행(오프라인 세션 로그인) |
| 밀리 프로세스 종료 후 남은 태스크(LoginActivity→EPubViewActivity)가 새 프로세스에서 복원되어 뷰어가 무한 로딩 | 프로세스가 없으면 `FLAG_ACTIVITY_CLEAR_TASK`로 새 태스크 시작. 프로세스가 있으면 기존 태스크로 복귀 |

대기 화면: 흰 배경, 애니메이션 없음, 텍스트 갱신 5초 간격.

## 빌드

```
millie-launcher/build.sh                           # 텍스트 로고
millie-launcher/build.sh --apk Millie-2.4.0.0.apk  # APK에서 로고·아이콘 추출
```

출력: `millie-launcher/out/millie-launcher.apk`. 서명 키 `local.keystore`가 없으면 생성한다.

## 설치

```
./install.sh --launcher [--apk Millie-2.4.0.0.apk]
```

다른 키로 서명된 래퍼가 설치되어 있으면 제거 후 재설치한다. `--webcompat`과 함께 설치하면 `kr.co.millie.eink.shownsignal`도 설치된다.

## 크레마 홈 연동

[`crema-home`](../crema-home/)은 밀리를 항상 이 래퍼로 실행한다. 래퍼가 설치되어 있으면 앱 목록에서 `kr.co.millie.eink` 항목을 숨긴다.
