import { tw } from '../tailwindStyles'
import { useState, type FormEvent } from 'react'
import { getMe, login, signUp, type User } from '../api/auth'
import { Brand, Button } from '../components/ui'

export default function AuthPage({
  kind,
  back,
  switchTo,
  onSuccess,
  onError,
}: {
  kind: 'login' | 'signup'
  back: () => void
  switchTo: () => void
  onSuccess: (user: User) => void
  onError: (error: unknown) => void
}) {
  const [email, setEmail] = useState(''),
    [password, setPassword] = useState(''),
    [nickname, setNickname] = useState(''),
    [busy, setBusy] = useState(false)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setBusy(true)
    try {
      if (kind === 'signup') await signUp(nickname.trim(), email.trim(), password)
      await login(email.trim(), password)
      setPassword('')
      onSuccess(await getMe())
    } catch (error) {
      onError(error)
    } finally {
      setBusy(false)
    }
  }
  return (
    <section className={tw('auth-screen')}>
      <Brand back={back} />
      <form className={tw('auth-form')} onSubmit={submit}>
        <h1>{kind === 'signup' ? '아이디어의 다음을 시작해요' : '다시 만나 반가워요'}</h1>
        <p className={tw('subcopy')}>
          {kind === 'signup'
            ? '나만의 아이디어 포트폴리오를 만들어 보세요.'
            : '아이디어의 변화를 이어서 살펴보세요.'}
        </p>
        {kind === 'signup' && (
          <label>
            이름
            <input
              required
              maxLength={50}
              autoComplete="nickname"
              placeholder="이름을 입력하세요"
              value={nickname}
              onChange={(event) => setNickname(event.target.value)}
            />
          </label>
        )}
        <label>
          이메일
          <input
            required
            type="email"
            maxLength={255}
            autoComplete="email"
            placeholder="이메일 주소를 입력하세요"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
        </label>
        <label>
          비밀번호
          <input
            required
            type="password"
            minLength={kind === 'signup' ? 8 : undefined}
            maxLength={72}
            autoComplete={kind === 'signup' ? 'new-password' : 'current-password'}
            placeholder="비밀번호를 입력하세요"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </label>
        <div className={tw('auth-actions')}>
          <Button type="submit" disabled={busy}>
            {busy ? '잠시만 기다려 주세요…' : kind === 'signup' ? '계정 만들기' : '로그인'}
          </Button>
          <button type="button" className={tw('text-link')} onClick={switchTo}>
            {kind === 'signup' ? '이미 계정이 있나요? 로그인' : '아직 계정이 없나요? 회원가입'}
          </button>
        </div>
      </form>
    </section>
  )
}
