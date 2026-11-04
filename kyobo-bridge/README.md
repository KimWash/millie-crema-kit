# kyobo-bridge

교보도서관 앱의 통신을 TLS 브리지로 보내는 설정. 브리지 바이너리는 제크리(jecrits)의 크레마 사운드업 시스템 이미지에 들어 있는 `millie-device-bridge`(긴꿈님 KitKat 브리지 기반)를 그대로 사용한다. 바이너리는 저장소에 포함하지 않는다.

## 필요한 이유

- Android 4.2의 TLS·인증서로는 교보 서버와 도서관 사이트에 접속할 수 없다.
- 브리지는 기기 안에서 교보 앱의 HTTPS를 받아 최신 TLS로 다시 보내고, 교보 응답 일부(업데이트 안내 등)를 고친다.

## 이 키트에서 바꾼 점 (사운드업 이미지 대비)

| 항목 | 사운드업 이미지 | kyobo-bridge |
|---|---|---|
| 리다이렉트 대상 | root 외 모든 앱의 80/443/8000~8999 | 교보 앱 uid만 (`iptables -m owner --uid-owner`) |
| 밀리와 전환 | 앱 실행을 감지해 프록시 켜고 끔 | 필요 없음 (밀리는 리다이렉트되지 않음) |
| 브리지 CA | 이미지에 들어 있는 공용 CA | 기기에서 `--init-ca`로 새로 생성. 인증서만 시스템 저장소에 추가 |
| 부팅 실행 | `install-recovery.sh` 교체 | 기존 `install-recovery.sh`에 한 줄 추가 |

## 사용

```
kyobo-bridge/extract.sh <crema-soundup-...-system-by-jecrits-*.img>   # bin/millie-device-bridge 추출
kyobo-bridge/setup.sh                                                  # 설치 (루트 필요)
kyobo-bridge/remove.sh                                                 # 제거
```

| 파일 | 설치 위치 |
|---|---|
| 브리지 바이너리 | `/data/local/millie_bridge/bin/millie-device-bridge` (root 전용) |
| 기기 CA 키·인증서 | `/data/local/millie_bridge/ca.*` (키는 0600) |
| CA 인증서 | `/system/etc/security/cacerts/<hash>.N` |
| 부팅 스크립트 | `/system/etc/kyobo-bridge.sh` |
| 로그 | `/data/local/millie_bridge/kyobo-bridge.log` |
| 복원 | `/sdcard/millie-crema-backup/<시각>-kyobo-bridge/restore.sh` |

- 교보 앱을 다시 설치하면 uid가 바뀔 수 있다. `setup.sh`를 다시 실행하거나 재부팅한다(부팅 시 uid를 다시 읽음).
- 브리지는 시작할 때 시스템 루트 인증서를 읽는다. 루트 인증서를 추가한 뒤에는 `/system/etc/kyobo-bridge.sh`를 다시 실행한다.

## 루트 인증서

도서관 서버마다 인증서 발급 기관이 다르다(예: 인천대 `ebook.inu.ac.kr`는 Sectigo Public Server Authentication Root R46). 기기 저장소에 없는 루트는 브리지가 `x509: certificate signed by unknown authority`로 거부하고, 교보 앱 화면에 이 문구가 그대로 표시된다. macOS 시스템 루트 중 TLS용으로 기기에 없는 것을 같은 형식(`<subject_hash_old>.N`, PEM + text)으로 추가하면 해결된다.

## 출처

- 교보도서관 1.2.0 물리키 패치: nerious2
- 검색 API·웹방화벽 수정판: 연어파 (`KyoboLibrary_1.2.0_patched_by_salmonpa_260929.apk`, e북카페 958057)
- 브리지: 긴꿈 (millie-kitkat-enabler, e북카페 932724), 사운드업 이미지 제크리 (e북카페 946020)
