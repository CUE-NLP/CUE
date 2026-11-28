import { tw } from '../tailwindStyles'
import { useEffect, useRef, useState } from 'react'
import { BottomNav, Brand, Button } from '../components/ui'
type Mode = 'keyboard' | 'voice'
type SpeechResult = { isFinal: boolean; 0: { transcript: string } }
type SpeechEvent = { resultIndex: number; results: ArrayLike<SpeechResult> }
type SpeechRecognitionLike = {
  lang: string
  continuous: boolean
  interimResults: boolean
  onresult: ((event: SpeechEvent) => void) | null
  onerror: ((event: { error: string }) => void) | null
  onend: (() => void) | null
  start(): void
  stop(): void
}
type SpeechConstructor = new () => SpeechRecognitionLike
function speechConstructor(): SpeechConstructor | undefined {
  const browser = window as Window & {
    SpeechRecognition?: SpeechConstructor
    webkitSpeechRecognition?: SpeechConstructor
  }
  return browser.SpeechRecognition || browser.webkitSpeechRecognition
}
function KeyboardIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 20 20" fill="none" aria-hidden="true">
      <rect x="2" y="5" width="16" height="10" rx="2" stroke="currentColor" strokeWidth="1.6" />
      <path
        d="M5.5 8.5h1M9.5 8.5h1M13.5 8.5h1M6.5 11.5h7"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
      />
    </svg>
  )
}
function MicIcon({ size }: { size: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" aria-hidden="true">
      <rect x="7" y="2.5" width="6" height="10" rx="3" stroke="currentColor" strokeWidth="1.6" />
      <path
        d="M4.5 10a5.5 5.5 0 0 0 11 0M10 15.5V18"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
      />
    </svg>
  )
}

export default function InputPage({
  raw,
  setRaw,
  back,
  review,
  busy,
  onError,
  home = false,
  go,
  alerts,
}: {
  raw: string
  setRaw: (next: string | ((old: string) => string)) => void
  back: () => void
  review: () => void
  busy: boolean
  onError: (error: unknown) => void
  home?: boolean
  go?: (page: 'home' | 'leaderboard' | 'mine' | 'settings') => void
  alerts?: () => void
}) {
  const [mode, setMode] = useState<Mode>('keyboard'),
    [interim, setInterim] = useState(''),
    [recording, setRecording] = useState(false),
    [seconds, setSeconds] = useState(0)
  const recognition = useRef<SpeechRecognitionLike | null>(null)
  const meterStream = useRef<MediaStream | null>(null)
  const meterContext = useRef<AudioContext | null>(null)
  const meterFrame = useRef<number | null>(null)
  const meterActive = useRef(false)
  const waveform = useRef<HTMLDivElement>(null)
  function stopMeter() {
    meterActive.current = false
    if (meterFrame.current != null) cancelAnimationFrame(meterFrame.current)
    meterFrame.current = null
    meterStream.current?.getTracks().forEach((track) => track.stop())
    meterStream.current = null
    void meterContext.current?.close()
    meterContext.current = null
    waveform.current
      ?.querySelectorAll('i')
      .forEach((bar) => ((bar as HTMLElement).style.height = '5px'))
  }
  async function startMeter() {
    if (!navigator.mediaDevices?.getUserMedia) return
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      if (!meterActive.current) {
        stream.getTracks().forEach((track) => track.stop())
        return
      }
      const context = new AudioContext()
      const source = context.createMediaStreamSource(stream)
      const analyser = context.createAnalyser()
      analyser.fftSize = 1024
      source.connect(analyser)
      meterStream.current = stream
      meterContext.current = context
      const samples = new Uint8Array(analyser.fftSize)
      const render = () => {
        if (!meterActive.current) return
        analyser.getByteTimeDomainData(samples)
        const level = Math.sqrt(
          samples.reduce((sum, value) => sum + ((value - 128) / 128) ** 2, 0) / samples.length,
        )
        waveform.current?.querySelectorAll('i').forEach((bar, index) => {
          const shape = 0.45 + 0.55 * Math.abs(Math.sin(index * 1.7 + performance.now() / 130))
          ;(bar as HTMLElement).style.height = `${Math.min(44, 5 + level * 165 * shape)}px`
        })
        meterFrame.current = requestAnimationFrame(render)
      }
      render()
    } catch {
      // 음성 인식은 별도로 동작한다. 진폭 권한이 없으면 파형을 기본 높이로 둔다.
    }
  }
  useEffect(
    () => () => {
      recognition.current?.stop()
      stopMeter()
    },
    [],
  )
  useEffect(() => {
    if (!recording) return
    const timer = window.setInterval(() => setSeconds((old) => old + 1), 1000)
    return () => clearInterval(timer)
  }, [recording])
  function stop() {
    recognition.current?.stop()
    stopMeter()
    setRecording(false)
  }
  function start() {
    const Speech = speechConstructor()
    if (!Speech) {
      onError(
        new Error(
          '이 브라우저는 음성 받아쓰기를 지원하지 않습니다. Chrome에서 시도하거나 키보드로 입력해 주세요.',
        ),
      )
      return
    }
    const instance = new Speech()
    instance.lang = 'ko-KR'
    instance.continuous = true
    instance.interimResults = true
    instance.onresult = (event) => {
      let final = '',
        partial = ''
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i]
        if (result.isFinal) final += `${result[0].transcript.trim()} `
        else partial += result[0].transcript
      }
      if (final) setRaw((old) => `${old}${old && !old.endsWith(' ') ? ' ' : ''}${final}`)
      setInterim(partial)
    }
    instance.onerror = (event) =>
      onError(
        new Error(
          `음성 인식 오류: ${event.error}. 마이크 권한을 확인하거나 키보드로 입력해 주세요.`,
        ),
      )
    instance.onend = () => {
      setRecording(false)
      setInterim('')
      recognition.current = null
      stopMeter()
    }
    recognition.current = instance
    try {
      instance.start()
      setRecording(true)
      setSeconds(0)
      meterActive.current = true
      void startMeter()
    } catch {
      onError(new Error('마이크를 시작하지 못했습니다. 브라우저 권한을 확인해 주세요.'))
    }
  }
  function next() {
    if (recording) stop()
    if (!raw.trim()) {
      onError(new Error('아이디어를 먼저 입력해 주세요.'))
      return
    }
    review()
  }
  return (
    <section className={`input-screen ${tw('input-screen')} ${home ? 'record-home' : ''}`}>
      {home ? (
        <Brand onBell={alerts} />
      ) : (
        <Brand
          back={() => {
            stop()
            back()
          }}
        />
      )}
      {home && <p className="mt-4 text-[12px] font-bold text-[#6938ff]">아이디어 기록</p>}
      <h1 className="font-bold">
        {mode === 'voice' ? '어떤 생각이 떠올랐나요?' : '떠오른 생각을 적어보세요'}
      </h1>
      <div className={tw('mode-row')}>
        <p>{mode === 'voice' ? '편하게 적어주세요.' : '정리되지 않은 문장도 괜찮아요.'}</p>
        <div
          className={`mode-toggle ${tw('mode-toggle')} ${mode === 'voice' ? 'voice-selected' : ''}`}
          role="group"
          aria-label="입력 방식"
        >
          <button
            className={tw(mode === 'keyboard' ? 'selected' : '')}
            aria-label="키보드 입력"
            aria-pressed={mode === 'keyboard'}
            onClick={() => {
              stop()
              setMode('keyboard')
            }}
          >
            <KeyboardIcon />
          </button>
          <button
            className={tw(mode === 'voice' ? 'selected' : '')}
            aria-label="음성 입력"
            aria-pressed={mode === 'voice'}
            onClick={() => setMode('voice')}
          >
            <MicIcon size={18} />
          </button>
        </div>
      </div>
      {mode === 'keyboard' ? (
        <textarea
          key="keyboard"
          data-input-mode="keyboard"
          className={tw('idea-input')}
          aria-label="아이디어 내용"
          maxLength={5000}
          placeholder="혼자 외국어 회화를 연습할 때 대화 상대를 구하기 어려워요. AI 캐릭터와 실시간으로 대화하면서 부담 없이 연습할 수 있으면 좋겠어요."
          value={raw}
          onChange={(event) => setRaw(event.target.value)}
        />
      ) : (
        <div
          key="voice"
          data-input-mode="voice"
          className={tw(`voice-panel ${raw ? 'has-text' : ''}`)}
        >
          <span className={tw('voice-caption')}>
            {recording
              ? `● 녹음 중　${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
              : raw
                ? '음성 입력 완료'
                : '음성으로 아이디어 남기기'}
          </span>
          <div className={tw('transcript')}>
            {raw || interim ? (
              <>
                {raw}
                <span className={tw('interim')}>{interim}</span>
              </>
            ) : (
              <span className={tw('voice-placeholder')}>
                떠오른 생각을
                <br />
                편하게 이야기해 주세요.
              </span>
            )}
          </div>
          <div
            ref={waveform}
            className={`waveform ${recording ? 'active' : ''} ${tw(`waveform ${recording ? 'active' : ''}`)}`}
            aria-hidden="true"
          >
            {Array.from({ length: 25 }, (_, i) => (
              <i key={i} style={{ height: '5px' }} />
            ))}
          </div>
          <button
            className={`mic-button ${tw('mic-button')} ${recording ? 'recording' : ''}`}
            aria-label={recording ? '녹음 중지' : '녹음 시작'}
            onClick={recording ? stop : start}
          >
            {recording ? (
              <span className="stop-square" aria-hidden="true" />
            ) : (
              <MicIcon size={32} />
            )}
          </button>
          <span className={tw('voice-tip')}>
            {recording
              ? '눌러서 녹음을 마칠 수 있어요.'
              : raw
                ? '다시 녹음하려면 마이크를 눌러주세요.'
                : '마이크를 눌러 녹음을 시작하세요.'}
          </span>
        </div>
      )}
      <div className={`bottom-actions ${tw('bottom-actions')}`}>
        <Button
          // 구조화 버튼은 입력이 비어 있거나 AI가 정리하는 동안 누를 수 없다 (녹음 시작·녹음 마치기 버튼은 그대로 눌린다)
          disabled={busy || (!recording && !(mode === 'voice' && !raw) && !raw.trim())}
          onClick={mode === 'voice' && !raw && !recording ? start : next}
        >
          {busy
            ? '정리하는 중…'
            : mode === 'voice' && !raw && !recording
              ? '녹음 시작'
              : recording
                ? '녹음 마치고 확인'
                : '구조화하기'}
        </Button>
      </div>
      {home && go && (
        <BottomNav
          current="home"
          go={(page) => {
            stop()
            go(page)
          }}
        />
      )}
    </section>
  )
}
