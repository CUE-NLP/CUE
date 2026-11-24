import { tw } from './tailwindStyles'
import { useEffect, useRef, useState } from 'react'
import { getMe, logout, type User } from './api/auth'
import { authStore, ApiError } from './api/client'
import {
  createIdea,
  evaluateIdea,
  getIdea,
  getIdeas,
  markIdeaChecked,
  structureIdea,
  updateIdea,
  updateWeights,
  type Evaluation,
  type Idea,
  type IdeaFields,
  type ScoreType,
} from './api/ideas'
import ErrorBanner, { errorText } from './components/ErrorBanner'
import {
  ChangeModal,
  DocumentModal,
  EditModal,
  SettingsModal,
  SummaryModal,
  WeightsModal,
  WeightsPicker,
} from './components/IdeaModals'
import AuthPage from './pages/AuthPage'
import DetailPage from './pages/DetailPage'
import InputPage from './pages/InputPage'
import LeaderboardPage from './pages/LeaderboardPage'
import ResultPage from './pages/ResultPage'
import ReviewPage from './pages/ReviewPage'
import WelcomePage from './pages/WelcomePage'

type Page =
  | 'welcome'
  | 'login'
  | 'signup'
  | 'home'
  | 'leaderboard'
  | 'mine'
  | 'input'
  | 'review'
  | 'result'
  | 'detail'
type Overlay =
  | 'weights-picker'
  | 'weights'
  | 'edit'
  | 'document'
  | 'changes'
  | 'settings'
  | 'summary'
  | null
const emptyFields: IdeaFields = {
  title: '',
  target: '',
  problem: '',
  solution: '',
  expectation: '',
}

export default function App() {
  const [page, setPage] = useState<Page>(authStore.get() ? 'home' : 'welcome')
  const [user, setUser] = useState<User | null>(null),
    [selected, setSelected] = useState<Idea | null>(null)
  const [raw, setRaw] = useState(''),
    [fields, setFields] = useState<IdeaFields>({ ...emptyFields })
  const [busy, setBusy] = useState(false),
    [structuring, setStructuring] = useState(false),
    [error, setError] = useState(''),
    [overlay, setOverlay] = useState<Overlay>(null)
  const [weightTarget, setWeightTarget] = useState<Idea | null>(null)
  const [document, setDocument] = useState<{
    reference: Evaluation['references'][number]
    evaluation: Evaluation
  } | null>(null)
  const [changes, setChanges] = useState<Idea[]>([])
  // 마지막으로 구조화에 성공한 원문. 같은 글로 다시 누르면 AI를 부르지 않는다
  const structuredText = useRef('')
  function fail(cause: unknown) {
    setError(errorText(cause))
    if (cause instanceof ApiError && cause.status === 401) {
      authStore.clear()
      setOverlay(null)
      setPage('login')
    }
  }
  function navigate(next: Page) {
    setError('')
    setPage(next)
  }
  useEffect(() => {
    if (!authStore.get()) return
    let active = true
    getMe()
      .then((me) => {
        if (active) {
          setUser(me)
          setPage('home')
        }
      })
      .catch((error) => {
        if (active) fail(error)
      })
    return () => {
      active = false
    }
  }, [])
  useEffect(() => {
    if (
      !selected ||
      !['result', 'detail'].includes(page) ||
      !['QUEUED', 'RUNNING'].includes(selected.evaluationStatus)
    )
      return
    const timer = window.setInterval(() => getIdea(selected.id).then(setSelected).catch(fail), 3000)
    return () => clearInterval(timer)
  }, [selected?.id, selected?.evaluationStatus, page])
  function open(idea: Idea, next: Page = 'detail') {
    setSelected(idea)
    navigate(next)
    getIdea(idea.id).then(setSelected).catch(fail)
  }
  function startIdea() {
    setRaw('')
    setFields({ ...emptyFields })
    structuredText.current = ''
    navigate('input')
  }
  async function beginReview() {
    const text = raw.trim()
    // 글을 바꾸지 않았으면 이미 받은 결과와 사용자가 고친 내용을 그대로 쓴다
    if (text === structuredText.current) {
      navigate('review')
      return
    }
    setStructuring(true)
    setError('')
    try {
      const result = await structureIdea(text)
      setFields({
        title: result.title ?? '',
        target: result.target,
        problem: result.problem,
        solution: result.solution,
        expectation: result.expectation,
      })
      structuredText.current = text
      navigate('review')
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 401) {
        fail(cause)
        return
      }
      // AI가 바쁘거나(E006) 너무 빨리 다시 눌렀을 때(I006) 등은 직접 채우도록 원문을 문제 칸에 넣고 넘어간다
      if (structuredText.current) {
        structuredText.current = ''
        setFields({ ...emptyFields, problem: text })
      } else setFields((old) => ({ ...old, problem: old.problem || text }))
      navigate('review')
      const code = cause instanceof ApiError ? ` (${cause.code})` : ''
      setError(`자동 정리를 하지 못했어요${code}. 네 항목을 직접 채워 주세요.`)
    } finally {
      setStructuring(false)
    }
  }
  async function saveIdea() {
    setBusy(true)
    setError('')
    try {
      const idea = await createIdea(fields)
      let evaluateError: unknown = null
      if (idea.status === 'ACTIVE') {
        // 네 칸이 다 찬 아이디어는 저장하자마자 평가를 요청해 바로 평가 중 화면을 띄운다
        try {
          const result = await evaluateIdea(idea.id)
          setSelected({ ...result.idea, evaluationStatus: result.status })
        } catch (cause) {
          evaluateError = cause
          setSelected(await getIdea(idea.id).catch(() => idea))
        }
      } else {
        // 빈 칸이 있으면 보류(HOLD)로 저장되고 평가하지 않는다
        setSelected(await getIdea(idea.id).catch(() => idea))
      }
      setRaw('')
      setFields({ ...emptyFields })
      structuredText.current = ''
      navigate('result')
      if (evaluateError instanceof ApiError && evaluateError.status === 401) fail(evaluateError)
      else if (evaluateError) {
        const code = evaluateError instanceof ApiError ? ` (${evaluateError.code})` : ''
        setError(`저장은 됐지만 평가를 요청하지 못했어요${code}. 아래 버튼으로 다시 요청해 주세요.`)
      }
    } catch (cause) {
      fail(cause)
    } finally {
      setBusy(false)
    }
  }
  async function evaluate() {
    if (!selected) return
    setBusy(true)
    setError('')
    try {
      const result = await evaluateIdea(selected.id)
      setSelected({ ...result.idea, evaluationStatus: result.status })
    } catch (cause) {
      fail(cause)
    } finally {
      setBusy(false)
    }
  }
  async function check() {
    if (!selected) return
    try {
      setSelected(await markIdeaChecked(selected.id))
    } catch (cause) {
      fail(cause)
    }
  }
  async function saveEdit(next: Partial<IdeaFields>) {
    if (!selected) return
    setBusy(true)
    setError('')
    try {
      const saved = await updateIdea(selected.id, next)
      const titleOnly = next.title !== undefined && Object.keys(next).length === 1
      setSelected(titleOnly ? saved : await getIdea(saved.id).catch(() => saved))
      setOverlay(null)
    } catch (cause) {
      if (cause instanceof ApiError && cause.code === 'I005') {
        // 평가 중이라 거절됨: 모달을 닫아 안내가 보이게 하고, 평가 중 상태를 받아 자동 새로고침을 시작한다
        setOverlay(null)
        getIdea(selected.id)
          .then(setSelected)
          .catch(() => undefined)
      }
      fail(cause)
    } finally {
      setBusy(false)
    }
  }
  async function saveWeights(next: Record<ScoreType, number>) {
    if (!weightTarget) return
    setBusy(true)
    try {
      const idea = await updateWeights(weightTarget.id, next)
      setSelected((old) => (old?.id === idea.id ? idea : old))
      setOverlay(null)
    } catch (cause) {
      fail(cause)
    } finally {
      setBusy(false)
    }
  }
  async function showChanges() {
    try {
      const [active, hold] = await Promise.all([getIdeas('ACTIVE'), getIdeas('HOLD')])
      setChanges([...active.items, ...hold.items])
      setOverlay('changes')
    } catch (cause) {
      fail(cause)
    }
  }
  async function signOut() {
    setBusy(true)
    try {
      await logout()
      setUser(null)
      setSelected(null)
      setOverlay(null)
      navigate('welcome')
    } catch (cause) {
      fail(cause)
    } finally {
      setBusy(false)
    }
  }
  const weightSettings = (idea?: Idea) => {
    setWeightTarget(idea || null)
    setOverlay(idea ? 'weights' : 'weights-picker')
  }

  return (
    <main className={tw('app-shell')}>
      <div className={tw('app-card')}>
        {page === 'welcome' && <WelcomePage go={navigate} />}
        {(page === 'login' || page === 'signup') && (
          <AuthPage
            key={page}
            kind={page}
            back={() => navigate('welcome')}
            switchTo={() => navigate(page === 'login' ? 'signup' : 'login')}
            onSuccess={(me) => {
              setUser(me)
              navigate('home')
            }}
            onError={fail}
          />
        )}
        {(['home', 'leaderboard', 'mine'] as Page[]).includes(page) && (
          <LeaderboardPage
            view={page as 'home' | 'leaderboard' | 'mine'}
            open={(idea) => open(idea)}
            create={startIdea}
            settings={() => weightSettings()}
            account={() => setOverlay('settings')}
            alerts={() => void showChanges()}
            go={navigate}
            onError={fail}
          />
        )}
        {page === 'input' && (
          <InputPage
            raw={raw}
            setRaw={setRaw}
            back={() => navigate('home')}
            review={() => void beginReview()}
            busy={structuring}
            onError={fail}
          />
        )}
        {page === 'review' && (
          <ReviewPage
            raw={raw}
            fields={fields}
            setFields={setFields}
            back={() => navigate('input')}
            save={() => void saveIdea()}
            busy={busy}
          />
        )}
        {page === 'result' && selected && (
          <ResultPage
            idea={selected}
            back={() => navigate('mine')}
            openDetail={() => navigate('detail')}
            evaluate={() => void evaluate()}
            busy={busy}
          />
        )}
        {page === 'detail' && selected && (
          <DetailPage
            idea={selected}
            back={() => navigate('leaderboard')}
            edit={() => setOverlay('edit')}
            settings={() => weightSettings(selected)}
            summary={() => setOverlay('summary')}
            document={(reference, evaluation) => {
              setDocument({ reference, evaluation })
              setOverlay('document')
            }}
            check={() => void check()}
            onError={fail}
          />
        )}
        {overlay === 'weights-picker' && (
          <WeightsPicker
            close={() => setOverlay(null)}
            choose={(idea) => weightSettings(idea)}
            onError={fail}
          />
        )}
        {overlay === 'weights' && weightTarget && (
          <WeightsModal
            key={weightTarget.id}
            idea={weightTarget}
            close={() => setOverlay(null)}
            save={(next) => void saveWeights(next)}
            busy={busy}
          />
        )}
        {overlay === 'edit' && selected && (
          <EditModal
            key={selected.id}
            idea={selected}
            close={() => setOverlay(null)}
            save={(next) => void saveEdit(next)}
            busy={busy}
          />
        )}
        {overlay === 'document' && document && (
          <DocumentModal {...document} close={() => setOverlay(null)} />
        )}
        {overlay === 'summary' && selected && (
          <SummaryModal idea={selected} close={() => setOverlay(null)} />
        )}
        {overlay === 'changes' && (
          <ChangeModal
            ideas={changes}
            close={() => setOverlay(null)}
            open={(idea) => {
              setOverlay(null)
              open(idea)
            }}
          />
        )}
        {overlay === 'settings' && (
          <SettingsModal
            userName={user?.nickname || '사용자'}
            close={() => setOverlay(null)}
            logout={() => void signOut()}
          />
        )}
        <ErrorBanner message={error} close={() => setError('')} />
      </div>
    </main>
  )
}
