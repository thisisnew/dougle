import { beforeEach, expect, it } from 'vitest'
import { readPlanSubmission, removePlanSubmission, savePlanSubmission } from './planSubmissionStorage'

const scope = { workspaceId: 'synthetic', bundleId: 'bundle', bundleDigest: 'bundle-digest' }
const submission = { requestId: 'stable-request', bundleId: 'bundle', provider: 'CODEX', requestedModel: 'fixture', digest: 'plan-digest', approved: true as const }
beforeEach(() => { for (const key of Object.keys(sessionStorage)) if (key.startsWith('dougle.plan-submission.')) sessionStorage.removeItem(key) })

it('persists only bound approval metadata and restores the same request', () => {
  savePlanSubmission(scope, submission)
  expect(readPlanSubmission(scope)).toEqual(submission)
  expect(JSON.parse(sessionStorage.getItem('dougle.plan-submission.synthetic.bundle')!)).toEqual({ ...scope, submission })
})
it('rejects a changed bundle digest and cannot overwrite uncertain acceptance', () => {
  savePlanSubmission(scope, submission)
  expect(() => readPlanSubmission({ ...scope, bundleDigest: 'other' })).toThrow()
  expect(() => savePlanSubmission(scope, { ...submission, requestId: 'new-request' })).toThrow()
  expect(readPlanSubmission(scope)).toEqual(submission)
})
it('removes only the reconciled request and preserves unrelated session data', () => {
  sessionStorage.setItem('synthetic-unrelated', 'keep')
  savePlanSubmission(scope, submission)
  removePlanSubmission(scope, 'other')
  expect(readPlanSubmission(scope)).toEqual(submission)
  removePlanSubmission(scope, submission.requestId)
  expect(readPlanSubmission(scope)).toBeUndefined()
  expect(sessionStorage.getItem('synthetic-unrelated')).toBe('keep')
  sessionStorage.removeItem('synthetic-unrelated')
})
