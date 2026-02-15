# OCR 앱 트러블슈팅 기록 (2026-02-15)

이 문서는 `/Users/kmg/Project/ocr` 프로젝트에서 실제로 발생한 이슈와 해결 과정을 정리한 운영 문서입니다.

## 1) 요약
- 핵심 장애는 `SQLite lock`, `상태 표시 불일치`, `OCR 텍스트 레이어 품질/정렬` 이슈였다.
- 최종적으로 **이미지 + 숨김 텍스트 레이어(원문 위치 고정)** 구조로 정리했다.
- Google Vision API 호출 자체는 정상이며, 품질 문제는 주로 **PDF 텍스트 레이어 조합 방식**에서 발생했다.

## 2) 이슈별 상세

### TS-01. 폴더 선택 버튼 동작 불안정
- 증상:
  - `폴더 찾기` 클릭 시 창이 안 뜨거나 취소처럼 보임.
- 원인:
  - GUI/headless 환경 차이, OS별 picker 동작 편차.
- 조치:
  - macOS AppleScript picker 우선, 실패 시 Swing fallback.
  - headless일 때는 수동 경로 입력 메시지 반환.
- 관련 코드:
  - `/Users/kmg/Project/ocr/backend/src/main/java/com/kmg/ocr/service/FolderPickerService.java`
- 상태:
  - 해결(환경 의존 fallback 포함).

### TS-02. 작업 중 멈춤 + `SQLITE_BUSY (database is locked)`
- 증상:
  - 작업 중 실패, job/item에 DB lock 에러 기록.
- 원인:
  - 병렬 OCR 처리 중 SQLite 쓰기 경합(usage/job/item 동시 갱신).
- 조치:
  - Hikari pool을 1로 고정.
  - busy timeout 확대.
  - SQLite PRAGMA(WAL/synchronous/foreign_keys/busy_timeout) 적용.
  - OCR 루프 내 과도한 job status write 감소.
- 관련 코드:
  - `/Users/kmg/Project/ocr/backend/src/main/resources/application.yml`
  - `/Users/kmg/Project/ocr/backend/src/main/java/com/kmg/ocr/config/StartupInitializer.java`
  - `/Users/kmg/Project/ocr/backend/src/main/java/com/kmg/ocr/service/JobService.java`
- 상태:
  - 해결.

### TS-03. Job이 `RUNNING`으로 고정되거나 상태 불일치
- 증상:
  - `ended_at`이 있는데 status가 `RUNNING`으로 보이는 비정상 상태.
- 원인:
  - 실패/재시작 경계에서 상태 전이 정합성 부족.
- 조치:
  - start 시 상태 초기화(`prepareJobForRun`) 추가.
  - finally 구간에서 `RUNNING` 잔존 상태 정리(reconcile) 추가.
- 관련 코드:
  - `/Users/kmg/Project/ocr/backend/src/main/java/com/kmg/ocr/repo/JobRepository.java`
  - `/Users/kmg/Project/ocr/backend/src/main/java/com/kmg/ocr/service/JobService.java`
- 상태:
  - 해결.

### TS-04. 대시보드에서 “현재 몇 번째 파일” 표시 부족
- 증상:
  - 실행 중인데 현재 파일 인덱스가 안 보임.
- 조치:
  - `현재 폴더`, `현재 파일(n/m)`, `최근 완료 작업` 표시 추가.
  - SSE 끊김 대비 polling fallback 추가.
- 관련 코드:
  - `/Users/kmg/Project/ocr/frontend/src/App.jsx`
  - `/Users/kmg/Project/ocr/frontend/src/styles.css`
- 상태:
  - 해결.

### TS-05. “Google API 쓴 거 맞나?” OCR 품질 저하 이슈
- 증상:
  - 검색 텍스트에 공백/문장 깨짐 발생.
- 확인:
  - Vision API 원문(`fullTextAnnotation.text`)은 정상.
  - 즉 OCR 자체보다 PDF 텍스트 레이어 조합 품질 문제가 컸음.
- 조치:
  - 폰트 fallback 보강(`Arial Unicode` 우선 후보 추가).
  - 글리프 필터 로직 보정.
  - 라인 조합 로직 개선.
- 관련 코드:
  - `/Users/kmg/Project/ocr/backend/src/main/java/com/kmg/ocr/service/OcrService.java`
  - `/Users/kmg/Project/ocr/backend/src/main/java/com/kmg/ocr/service/PdfService.java`
- 상태:
  - 개선됨(완전 무오차는 OCR 본질 특성상 보장 불가).

### TS-06. 텍스트가 왼쪽 상단 블록으로 뭉침(드래그 위치 불일치)
- 증상:
  - 드래그 하이라이트가 실제 글자 위치와 어긋남.
- 원인:
  - fullText 블록을 페이지 상단 기준으로 배치한 방식.
- 조치:
  - 방식 재변경:
    - **원문 line 좌표 기반 배치(글자 위치 고정)**를 기본으로 유지
    - 한국어 조사 결합 규칙 일부 추가(`주식 의` -> `주식의` 계열 개선)
  - 필요 시 line 좌표가 없을 때만 블록 fallback 사용.
- 관련 코드:
  - `/Users/kmg/Project/ocr/backend/src/main/java/com/kmg/ocr/service/PdfService.java`
- 상태:
  - 해결(위치 정합성 복구).

### TS-07. 콘솔 수치(`109`)와 앱 수치 차이
- 증상:
  - “200장 넘게 했는데 Google 콘솔에 109만 보임”.
- 원인:
  - 콘솔에서 본 값은 `requests per minute`(분당 실시간 지표), 누적 월 사용량이 아님.
- 조치:
  - 앱 내부 누적(`usage_monthly.used_units`)과 콘솔 지표 해석 기준 분리 안내.
- 상태:
  - 오해 해소.

### TS-08. “토큰 아끼고 재조합만” 요구
- 증상:
  - OCR 재호출 없이 PDF 재생성 희망.
- 원인:
  - 완료 시점에 `ocr_page_checkpoints` 삭제 정책이라 전체 캐시 부재.
- 현재 상태:
  - 일부 실패 작업 캐시(소량)만 존재, 완료 218장 전체 재조합은 불가.
- 후속 개선 후보:
  - 완료 후 체크포인트 보존 옵션.
  - “캐시로 PDF 재생성” API/버튼 추가.

## 3) 최종 채택 방식 (현재)
- OCR: Google Vision `DOCUMENT_TEXT_DETECTION` (ko,en)
- PDF:
  - 원본 이미지 유지
  - 숨김 텍스트 레이어 추가(검색/복사 가능)
  - **라인 좌표 고정 방식**으로 드래그 위치 일치 우선
- 장애 대응:
  - SQLite 단일 커넥션 + WAL + timeout
  - 실패/중단 재시작 시 체크포인트 기반 이어하기

## 4) 운영 체크리스트
- 실행 전:
  - `/Users/kmg/Project/ocr/credentials` 키 존재 확인
  - 앱 재시작 후 Dashboard에서 활성 키/남은 한도 확인
- 실행 중:
  - Queue에서 이미지 개수 확인 후 시작
  - Dashboard의 현재 파일 진행률 확인
- 문제 발생 시:
  - `state/app.db`에서 `jobs`, `job_items`, `usage_monthly` 조회
  - `output/reports/<jobId>.json` 확인
  - `SQLITE_BUSY`면 동시 작업/중복 실행 여부 먼저 확인

## 5) 재현/검증에 사용한 대표 명령
```bash
# 최근 작업 상태
sqlite3 /Users/kmg/Project/ocr/state/app.db \
  "SELECT id,status,processed_items,total_items,started_at,ended_at FROM jobs ORDER BY created_at DESC LIMIT 10;"

# 아이템 상세
sqlite3 /Users/kmg/Project/ocr/state/app.db \
  "SELECT job_id,status,image_done,image_total,error_reason FROM job_items ORDER BY created_at DESC LIMIT 20;"

# 월간 사용량
sqlite3 /Users/kmg/Project/ocr/state/app.db \
  "SELECT credential_id,period_pt,used_units,cap_units FROM usage_monthly;"

# PDF 텍스트 확인
pdftotext /Users/kmg/Project/ocr/output/pdf/<파일명>.pdf -
```

## 6) 남은 개선 과제
- 완료 작업의 OCR 체크포인트 보존 정책(옵션화)
- 캐시 기반 무과금 PDF 재조합 기능
- 한국어 띄어쓰기 후처리 규칙 고도화(선택형)
