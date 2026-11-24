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
  return (
    <section className={tw('review-screen')}>
      <Brand back={back} />
      <h2>내가 적은 아이디어</h2>
      <blockquote>{raw}</blockquote>
      <h1>이렇게 이해했어요</h1>
      <p className={tw('subcopy')}>의도와 다르면 수정해 주세요.</p>
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
