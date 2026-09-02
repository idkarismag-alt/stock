# KIS Replay Android v2

Windows KIS LiveReplay와 **완전히 별도 관리되는 Android 프로젝트**입니다. Windows 소스를 그대로 실행하는 구조가 아니라, 전송팩 데이터 규격만 공유합니다.

## v2 핵심
- Windows → Android 전송팩 ZIP 가져오기
- 전송팩 SHA-256/파일크기 무결성 검사
- 0198 당일 급상승 저장 목록 + 실제 순위변동 표시
- 15% 연구 목록(고가/종가 기준)
- 일봉/1분봉 캔들 차트와 기본 Replay
- 1x~960x 배속, 표시봉 직접 입력
- 저장 분봉 우선 사용, 없을 때만 Kiwoom API 자동보완
- 저장된 학습자료 전체 보기
- Kiwoom App Key/Secret은 Android Keystore에 암호화 저장
- 전송팩 v2에서는 **0D 호가정보 완전 제외**
- 0B 체결은 raw JSON 없이 최소 컬럼만 경량 DB로 전송

## 데이터 이동
Windows에서 `pc_bridge/MAKE_ANDROID_PACK.bat` 실행 → `KIS_Android_Transfer_*.zip` 생성 → Android 앱의 데이터 메뉴에서 ZIP 선택.

## APK
`.github/workflows/build-apk.yml`이 main 브랜치 push 시 Debug APK를 자동 빌드하고 `KISReplayAndroid-v2-APK` artifact로 업로드합니다.
