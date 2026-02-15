import { useEffect, useMemo, useState } from 'react'

const tabs = ['dashboard', 'credentials', 'queue', 'history']

async function api(path, options = {}) {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options,
  })

  const raw = await res.text()
  const parseBody = () => {
    if (!raw) return null
    try {
      return JSON.parse(raw)
    } catch {
      return raw
    }
  }

  const body = parseBody()

  if (!res.ok) {
    let message = `HTTP ${res.status}`
    if (body && typeof body === 'object' && 'message' in body) {
      message = body.message || message
    } else if (typeof body === 'string' && body.trim()) {
      message = body.trim()
    }
    throw new Error(message)
  }

  return body
}

function Section({ title, children, right }) {
  return (
    <section className="card">
      <div className="card-header">
        <h3>{title}</h3>
        {right}
      </div>
      {children}
    </section>
  )
}

export default function App() {
  const [tab, setTab] = useState('dashboard')
  const [credentials, setCredentials] = useState([])
  const [jobs, setJobs] = useState([])
  const [links, setLinks] = useState(null)
  const [queueFolders, setQueueFolders] = useState([])
  const [manualPath, setManualPath] = useState('')
  const [strategy, setStrategy] = useState('MAX_REMAINING')
  const [parallelism, setParallelism] = useState(2)
  const [createdJobId, setCreatedJobId] = useState('')
  const [logs, setLogs] = useState([])
  const [error, setError] = useState('')
  const [queueNotice, setQueueNotice] = useState('')
  const [loading, setLoading] = useState(false)

  const runningJob = useMemo(() => jobs.find((j) => j.status === 'RUNNING'), [jobs])
  const latestCompletedJob = useMemo(() => jobs.find((j) => j.status === 'COMPLETED') || null, [jobs])
  const runningItem = useMemo(() => {
    if (!runningJob?.items?.length) return null
    return runningJob.items.find((item) => item.status === 'RUNNING') || null
  }, [runningJob])
  const runningFolderProgress = useMemo(() => {
    if (!runningJob || !runningItem) return '-'
    return `${runningItem.queueIndex + 1}/${runningJob.totalItems}`
  }, [runningJob, runningItem])
  const runningFileProgress = useMemo(() => {
    if (!runningItem) return '-'
    const current = runningItem.imageDone >= runningItem.imageTotal
      ? runningItem.imageTotal
      : runningItem.imageDone + 1
    return `${current}/${runningItem.imageTotal}`
  }, [runningItem])
  const completedProgress = useMemo(() => {
    if (!latestCompletedJob) return '-'
    return `${latestCompletedJob.processedItems}/${latestCompletedJob.totalItems}`
  }, [latestCompletedJob])

  useEffect(() => {
    refreshAll()
    const eventSource = new EventSource('/api/events')
    eventSource.onmessage = (event) => {
      try {
        const payload = JSON.parse(event.data)
        pushLog(`${payload.type}: ${payload.message || ''}`)
      } catch {
        pushLog(event.data)
      }
      refreshJobs()
      refreshCredentials()
    }
    eventSource.onerror = () => {
      pushLog('SSE disconnected, retrying...')
    }
    return () => eventSource.close()
  }, [])

  useEffect(() => {
    const pollId = window.setInterval(async () => {
      try {
        await refreshJobs()
        await refreshCredentials()
      } catch {
        // Keep UI responsive even when SSE temporarily disconnects.
      }
    }, 2500)

    return () => window.clearInterval(pollId)
  }, [])

  function pushLog(message) {
    setLogs((prev) => [{ message, ts: new Date().toISOString() }, ...prev].slice(0, 80))
  }

  async function refreshAll() {
    setLoading(true)
    setError('')
    try {
      await Promise.all([refreshCredentials(), refreshJobs(), refreshLinks()])
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  async function refreshCredentials() {
    const data = await api('/api/credentials')
    setCredentials(data)
  }

  async function refreshJobs() {
    const data = await api('/api/jobs')
    setJobs(data)
  }

  async function refreshLinks() {
    const data = await api('/api/meta/external-links')
    setLinks(data)
  }

  async function scanCredentials() {
    try {
      const data = await api('/api/credentials/scan', { method: 'POST' })
      setCredentials(data)
      pushLog('Credentials scanned')
    } catch (e) {
      setError(e.message)
    }
  }

  async function adjustUsage(id, currentUsed) {
    const raw = prompt(`새 used 값 입력 (현재 ${currentUsed})`)
    if (raw == null) return
    const usedOverride = Number(raw)
    if (!Number.isFinite(usedOverride) || usedOverride < 0) {
      alert('숫자를 입력하세요')
      return
    }
    const reason = prompt('보정 사유 입력')
    if (!reason) return

    try {
      await api(`/api/credentials/${id}/usage`, {
        method: 'PATCH',
        body: JSON.stringify({ usedOverride, reason }),
      })
      await refreshCredentials()
      pushLog(`Usage adjusted: ${id}`)
    } catch (e) {
      setError(e.message)
    }
  }

  async function pickFolder() {
    setQueueNotice('')
    try {
      const result = await api('/api/system/pick-folder', { method: 'POST' })
      if (result.cancelled || !result.path) {
        const msg = result.message || '폴더 선택이 취소되었습니다.'
        setQueueNotice(msg)
        pushLog(msg)
        return
      }
      await addFolder(result.path)
    } catch (e) {
      setError(e.message)
    }
  }

  async function addFolder(path) {
    const normalized = path.trim()
    setQueueNotice('')
    if (!normalized) {
      setQueueNotice('경로를 먼저 입력하세요.')
      return
    }
    if (queueFolders.some((f) => f.path === normalized)) {
      setQueueNotice('이미 큐에 있는 폴더입니다.')
      return
    }

    try {
      const stats = await api(`/api/folders/stats?path=${encodeURIComponent(normalized)}`)
      const resolvedPath = stats.path || normalized
      if (queueFolders.some((f) => f.path === resolvedPath)) {
        setQueueNotice('이미 큐에 있는 폴더입니다.')
        return
      }
      if (stats.imageCount <= 0) {
        setQueueNotice('이미지 파일(png/jpg/jpeg/webp)이 없는 폴더입니다.')
      } else {
        setQueueNotice(`폴더 추가 완료: ${resolvedPath} (이미지 ${stats.imageCount}장)`)
      }
      setQueueFolders((prev) => [...prev, { path: resolvedPath, stats: { ...stats, path: resolvedPath }, error: '' }])
      setManualPath('')
    } catch (e) {
      setQueueNotice(`폴더 추가 실패: ${e.message}`)
    }
  }

  async function createJob(autoStart = false) {
    if (queueFolders.length === 0) {
      setError('큐에 폴더가 없습니다.')
      return
    }

    try {
      const payload = {
        folders: queueFolders.map((f) => f.path),
        strategy,
        parallelism: Number(parallelism) || 2,
      }
      const result = await api('/api/jobs', {
        method: 'POST',
        body: JSON.stringify(payload),
      })
      setCreatedJobId(result.jobId)
      pushLog(`Job created: ${result.jobId}`)
      await refreshJobs()
      if (autoStart) {
        await startJob(result.jobId)
      }
    } catch (e) {
      setError(e.message)
    }
  }

  async function startJob(jobId = createdJobId) {
    if (!jobId) return
    try {
      await api(`/api/jobs/${jobId}/start`, { method: 'POST' })
      pushLog(`Job started: ${jobId}`)
      await refreshJobs()
    } catch (e) {
      setError(e.message)
    }
  }

  async function stopJob(jobId) {
    if (!jobId) return
    try {
      await api(`/api/jobs/${jobId}/stop`, { method: 'POST' })
      pushLog(`Stop requested: ${jobId}`)
      await refreshJobs()
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <div className="app">
      <header className="topbar">
        <div>
          <h1>OCR Local Manager</h1>
          <p>Google Vision OCR to Searchable PDF</p>
        </div>
        <div className="top-actions">
          <button onClick={refreshAll} disabled={loading}>{loading ? '갱신중...' : '새로고침'}</button>
        </div>
      </header>

      {links && (
        <Section
          title="Google Cloud 빠른 이동"
          right={<span className="muted">프로젝트: primal-abacus-485312-f6</span>}
        >
          <div className="link-row">
            <a className="btn-link" href={links.keyCreationUrl} target="_blank" rel="noopener noreferrer">키 생성</a>
            <a className="btn-link" href={links.keyMonitoringUrl} target="_blank" rel="noopener noreferrer">키 모니터링</a>
          </div>
        </Section>
      )}

      <nav className="tabs">
        {tabs.map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={tab === t ? 'active' : ''}
          >
            {t.toUpperCase()}
          </button>
        ))}
      </nav>

      {error && <div className="error">{error}</div>}

      {tab === 'dashboard' && (
        <div className="grid">
          <Section title="현재 상태">
            <div className="kv">
              <div><span>활성 키 수</span><strong>{credentials.filter((c) => c.status === 'ACTIVE').length}</strong></div>
              <div><span>총 남은 한도</span><strong>{credentials.reduce((acc, c) => acc + c.remainingUnits, 0)}</strong></div>
              <div><span>실행 중 작업</span><strong>{runningJob ? runningJob.id : '없음'}</strong></div>
              <div><span>현재 폴더</span><strong>{runningFolderProgress}</strong></div>
              <div><span>현재 파일</span><strong>{runningFileProgress}</strong></div>
              <div><span>최근 완료 작업</span><strong>{latestCompletedJob ? latestCompletedJob.id : '없음'}</strong></div>
              <div><span>완료 진행</span><strong>{completedProgress}</strong></div>
            </div>
            {runningItem && <p className="muted inline-note">작업중 경로: {runningItem.folderPath}</p>}
            {latestCompletedJob && (
              <p className="muted inline-note">
                최근 완료 시각: {latestCompletedJob.endedAt ? new Date(latestCompletedJob.endedAt).toLocaleString() : '-'}
              </p>
            )}
          </Section>
          <Section title="실시간 로그">
            <div className="logs">
              {logs.length === 0 && <p className="muted">로그 없음</p>}
              {logs.map((log, idx) => (
                <div key={`${log.ts}-${idx}`} className="log-item">
                  <span>{new Date(log.ts).toLocaleTimeString()}</span>
                  <span>{log.message}</span>
                </div>
              ))}
            </div>
          </Section>
        </div>
      )}

      {tab === 'credentials' && (
        <Section
          title="키 관리"
          right={<button onClick={scanCredentials}>스캔/동기화</button>}
        >
          <table>
            <thead>
              <tr>
                <th>파일</th>
                <th>계정</th>
                <th>사용</th>
                <th>남음</th>
                <th>리셋</th>
                <th>상태</th>
                <th>액션</th>
              </tr>
            </thead>
            <tbody>
              {credentials.map((c) => (
                <tr key={c.id}>
                  <td>{c.fileName}</td>
                  <td>{c.serviceAccountEmail}</td>
                  <td>{c.usedUnits}/{c.capUnits}</td>
                  <td>{c.remainingUnits}</td>
                  <td>{new Date(c.resetAt).toLocaleString()}</td>
                  <td>{c.status}</td>
                  <td>
                    <button onClick={() => adjustUsage(c.id, c.usedUnits)}>보정</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>
      )}

      {tab === 'queue' && (
        <div className="grid">
          <Section title="폴더 큐 설정">
            <div className="row">
              <button onClick={pickFolder}>폴더 찾기(자동)</button>
              <input
                placeholder="/path/to/run_folder (수동 입력)"
                value={manualPath}
                onChange={(e) => setManualPath(e.target.value)}
              />
              <button onClick={() => addFolder(manualPath)}>경로 추가(수동)</button>
            </div>
            <p className="muted">
              폴더 찾기(자동): 시스템 폴더 선택창으로 경로 자동 입력.
              경로 추가(수동): 직접 입력한 경로를 큐에 추가.
            </p>
            {queueNotice && <div className="notice">{queueNotice}</div>}
            <div className="row">
              <label>
                전략
                <select value={strategy} onChange={(e) => setStrategy(e.target.value)}>
                  <option value="MAX_REMAINING">MAX_REMAINING</option>
                  <option value="ROUND_ROBIN">ROUND_ROBIN</option>
                  <option value="FILENAME_ORDER">FILENAME_ORDER</option>
                </select>
              </label>
              <label>
                병렬수
                <input
                  type="number"
                  min="1"
                  max="8"
                  value={parallelism}
                  onChange={(e) => setParallelism(e.target.value)}
                />
              </label>
            </div>
            <div className="row">
              <button onClick={() => createJob(false)}>큐 생성</button>
              <button onClick={() => createJob(true)}>큐 생성 + 시작</button>
              <button onClick={() => setQueueFolders([])}>큐 비우기</button>
            </div>

            <table>
              <thead>
                <tr>
                  <th>폴더</th>
                  <th>이미지 개수</th>
                  <th>상태</th>
                </tr>
              </thead>
              <tbody>
                {queueFolders.map((f, idx) => (
                  <tr key={`${f.path}-${idx}`}>
                    <td>{f.path}</td>
                    <td>{f.stats ? f.stats.imageCount : '-'}</td>
                    <td>{f.error || 'READY'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Section>

          <Section title="작업 실행">
            <div className="row">
              <input value={createdJobId} onChange={(e) => setCreatedJobId(e.target.value)} placeholder="job id" />
              <button onClick={() => startJob(createdJobId)} disabled={!createdJobId}>시작</button>
              <button onClick={() => stopJob(createdJobId || runningJob?.id)} disabled={!createdJobId && !runningJob}>중지</button>
            </div>
            {runningJob && (
              <div className="running">
                <p><strong>Running:</strong> {runningJob.id}</p>
                <p>{runningJob.processedItems}/{runningJob.totalItems} 폴더 완료</p>
              </div>
            )}
          </Section>
        </div>
      )}

      {tab === 'history' && (
        <Section title="작업 이력">
          <table>
            <thead>
              <tr>
                <th>작업 ID</th>
                <th>상태</th>
                <th>진행</th>
                <th>오류</th>
                <th>결과</th>
                <th>액션</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((job) => (
                <tr key={job.id}>
                  <td>{job.id}</td>
                  <td>{job.status}</td>
                  <td>{job.processedItems}/{job.totalItems}</td>
                  <td>{job.lastError || job.stopReason || '-'}</td>
                  <td>
                    <div className="history-links">
                      {job.items
                        .filter((i) => i.pdfPath)
                        .map((i) => (
                          <a key={i.id} href={`file://${i.pdfPath}`} target="_blank" rel="noopener noreferrer">
                            {i.queueIndex + 1}.pdf
                          </a>
                        ))}
                    </div>
                  </td>
                  <td>
                    <button
                      onClick={() => {
                        setCreatedJobId(job.id)
                        startJob(job.id)
                      }}
                      disabled={!!runningJob && runningJob.id !== job.id}
                    >
                      재시작
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>
      )}
    </div>
  )
}
