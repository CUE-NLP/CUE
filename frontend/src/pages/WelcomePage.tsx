import { tw } from '../tailwindStyles'
import { Button } from '../components/ui'
export default function WelcomePage({ go }: { go: (page: 'login' | 'signup') => void }) {
  return (
    <section className={tw('welcome-screen')}>
      <div className={tw('hero-art')} aria-hidden="true">
        <div className={tw('orbit orbit-one')} />
        <div className={tw('orbit orbit-two')} />
        <span className={tw('art-mark')}>CUE</span>
        <span className={tw('art-float one')}>✦</span>
        <span className={tw('art-float two')}>◌</span>
        <span className={tw('art-float three')}>✧</span>
      </div>
      <div className={tw('welcome-copy')}>
        <h1>
          잠들어 있던 아이디어,
          <br />그 가능성의 무게를 재다
        </h1>
        <p>
          아이디어를 아카이빙하고 경쟁력을 지속적으로
          <br />
          검토하는 개인 아이디어 포트폴리오
        </p>
      </div>
      <div className={tw('welcome-actions')}>
        <Button onClick={() => go('signup')}>회원가입</Button>
        <Button secondary onClick={() => go('login')}>
          로그인
        </Button>
      </div>
    </section>
  )
}
