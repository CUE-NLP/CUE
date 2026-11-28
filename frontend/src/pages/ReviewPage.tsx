import { tw } from '../tailwindStyles'
import { IDEA_TITLE_MAX_LENGTH, type IdeaFields } from '../api/ideas'
import { Brand, Button, fieldLabels } from '../components/ui'
export default function ReviewPage({
  raw,
  fields,
  setFields,
  back,
  save,
  busy,
}: {
  raw: string
  fields: IdeaFields
  setFields: (fields: IdeaFields) => void
  back: () => void
  save: () => void
  busy: boolean
}) {
  // 원문에 근거가 없어 AI가 비워 둔 칸(또는 사용자가 지운 칸)을 알려 준다
  const empty = fieldLabels.filter(({ key }) => !fields[key].trim()).map(({ label }) => label)
  return (
    <section className={tw('review-screen')}>
      <Brand back={back} />
      <h2>내가 적은 아이디어 원문</h2>
      {/* 네 칸이 길어져도 원문 상자가 높이 0으로 줄어들지 않게 한다 (길면 상자 안에서 스크롤) */}
      <blockquote className="shrink-0">{raw}</blockquote>
      {empty.length > 0 && (
        <p className="m-0 rounded-xl bg-[#fff6e5] px-3.5 py-2.5 text-[13px] leading-normal text-[#8a5a00]">
          비어 있는 항목: {empty.join(', ')}. 채워야 평가를 받을 수 있어요.
        </p>
      )}
      <label className="mt-4 block text-sm font-semibold">
        아이디어 제목
        <input
          className="mt-2 w-full rounded-xl border border-[#c9b8ff] px-3 py-2"
          maxLength={IDEA_TITLE_MAX_LENGTH}
          placeholder="짧은 제목을 입력해 주세요"
          value={fields.title}
          onChange={(event) => setFields({ ...fields, title: event.target.value })}
        />
      </label>
      <div className={tw('field-list')}>
        {fieldLabels.map(({ key, label, hint }, index) => (
          <label className={tw('field-card')} key={key}>
            <span className={tw('number')}>{String(index + 1).padStart(2, '0')}</span>
            <span className={tw('field-body')}>
              <strong>{label}</strong>
              <textarea
                maxLength={5000}
                placeholder={hint}
                value={fields[key]}
                onChange={(event) => setFields({ ...fields, [key]: event.target.value })}
              />
            </span>
            <span className={tw('edit-icon')} aria-hidden="true">
              ✎
            </span>
          </label>
        ))}
      </div>
      <div className={tw('bottom-actions')}>
        <Button disabled={busy} onClick={save}>
          {busy ? '저장 중…' : '확인하고 평가하기 →'}
        </Button>
        <p className={tw('fine-print')}>구조화 네 항목 중 빈칸이 있으면 보류로 저장됩니다.</p>
      </div>
    </section>
  )
}
