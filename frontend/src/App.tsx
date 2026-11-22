import { tw } from './tailwindStyles'
import { useEffect, useState } from 'react'
import { getMe, logout, type User } from './api/auth'
import { authStore, ApiError } from './api/client'
import {
  createIdea,
  evaluateIdea,
  getIdea,
  getIdeas,
  markIdeaChecked,
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
const emptyFields: IdeaFields = { target: '', problem: '', solution: '', expectation: '' }

export default function App() {
  const [page, setPage] = useState<Page>(authStore.get() ? 'home' : 'welcome')
  const [user, setUser] = useState<User | null>(null),
    [selected, setSelected] = useState<Idea | null>(null)
  const [raw, setRaw] = useState(''),
    [fields, setFields] = useState<IdeaFields>({ ...emptyFields })
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [overlay, setOverlay] = useState<Overlay>(null)
  const [weightTarget, setWeightTarget] = useState<Idea | null>(null)
  const [document, setDocument] = useState<{
    reference: Evaluation['references'][number]
    evaluation: Evaluation
  } | null>(null)
  const [changes, setChanges] = useState<Idea[]>([])
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
    navigate('input')
  }
  function beginReview() {
    setFields((old) => ({ ...old, problem: old.problem || raw.trim() }))
    navigate('review')
  }
  async function saveIdea() {
    setBusy(true)
    setError('')
    try {
      const idea = await createIdea(fields)
      setSelected(idea)
      setRaw('')
      setFields({ ...emptyFields })
      navigate('result')
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
  async function saveEdit(next: IdeaFields) {
    if (!selected) return
    setBusy(true)
    try {
      setSelected(await updateIdea(selected.id, next))
      setOverlay(null)
    } catch (cause) {
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
            review={beginReview}
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
