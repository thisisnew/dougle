import assert from 'node:assert/strict'
import { isAbsolute } from 'node:path'

export const artifactSha256 = '5cf78b6441a99f893ecbaf6e81babd3e17e01e46b154f3c772e43abe138ca22a'
export const knownArtifacts = Object.freeze({
  [artifactSha256]: '0.1.25',
  f88d6f01b115c5951a98c35dd6723b447c4bce1da03843666a8ad5f64f368093: '0.1.26',
  '97c02f213455511766c475eeb5d934cac50614b7b0d864726b6213a76d36dcbc': '0.1.38',
})
export const documents = {
  'synthetic-context-badges.md': `# 합성 방문 배지 정의
BLUE 배지의 유효 기간은 발급 시각부터 72시간이다. 자동 갱신은 없고 운영 담당자의 수동 승인이 필요하다.
AMBER 배지의 유효 기간은 발급 시각부터 24시간이다. 자동 갱신은 없고 방문 안내 담당자의 수동 승인이 필요하다.
이 문서는 가상 정책 정의만 담는다. 현재 선택한 대상은 문서가 아니라 대화에서 정한다.
실제 배지 레코드, 발급 시각, 갱신 요청, 승인 완료 상태 및 운영 시스템은 존재하지 않는다.
`,
}
export const scenarios = [{ id: 'context-correction-restart', restartBeforeTurn: 3,
  questions: [[
    '가상 BLUE 배지 정책을 설명해줘. 유효 기간과 갱신 승인 주체는?',
    '앞서 말한 대상은 잘못 골랐어. 이제 BLUE가 아니라 AMBER를 대상으로 정정할게. 정정된 배지의 유효 기간은?',
    '그럼 그 배지도 자동 갱신돼? 누가 승인해야 해?',
    '우리가 마지막으로 정정한 배지 이름과 유효 기간, 갱신 승인 주체를 다시 알려줘.',
  ]],
  expected: [/72/, /24/, /방문\s*안내[\s\S]*수동|수동[\s\S]*방문\s*안내/, /AMBER[\s\S]*24|24[\s\S]*AMBER/],
  forbidden: [null, null, /자동으로 갱신(?:됩니다|돼요)/, null], expectedDecisions: ['STOP', 'STOP', 'STOP', 'STOP'],
  manualCriteria: 'Synthetic short-context probe, not full E04/E05: turn0 BLUE/72h/operator approval; turn1 explicit correction AMBER/24h; turn2 implicit AMBER/no auto-renewal/visitor-desk manual approval; turn3 after backend restart retains AMBER/24h/visitor-desk approval. Reject invented real issuance, approval or current status. Historical BLUE contrast is allowed if not current target. Four individually reviewed answers, no automatic semantic approval.',
}]

export function validateContextConfiguration(environment, maximumDispatches, variants, variantStart) {
  assert.equal(maximumDispatches, 8); assert.equal(variants, 1); assert.equal(variantStart, 0)
  validatePinnedRuntimeConfiguration(environment)
  assert.equal(environment.FORGE_EXPERT_PROTOCOL_COUNTER, undefined, 'Uninstrumented semantic probe')
}

export function validatePinnedRuntimeConfiguration(environment) {
  assert(Object.hasOwn(knownArtifacts, environment.FORGE_EXPERT_JAR_SHA256), 'Explicit known artifact required')
  assert.equal(environment.FORGE_EXPERT_READONLY_ARTIFACT_SHA256, environment.FORGE_EXPERT_JAR_SHA256)
  for (const key of ['FORGE_EXPERT_RESOURCES', 'FORGE_EXPERT_JAR', 'FORGE_EXPERT_SEARCH_MODELS', 'FORGE_EXPERT_PROFILE']) {
    const path = environment[key]
    assert(typeof path === 'string' && isAbsolute(path) && !path.includes('\0') && !path.split('/').includes('..'), 'Explicit resource path required: ' + key)
  }
}

export function isolatedRuntimeSettings(home, directory) {
  for (const path of [home, directory]) assert(typeof path === 'string' && isAbsolute(path)
    && !path.includes('\0') && !path.split('/').includes('..'), 'Owned absolute runtime path required')
  return {
    args: [`-Duser.home=${home}`, `-Djava.io.tmpdir=${directory}`],
    env: { HOME: home, TMPDIR: directory, PATH: '/usr/bin:/bin:/usr/sbin:/sbin', LANG: 'en_US.UTF-8', FORGE_DATA_DIR: directory, FORGE_DESKTOP: 'true' },
  }
}

export function contextApiAllowed(path, method) {
  if (method === 'GET') return ['/session', '/search-model'].includes(path)
    || /^\/workspaces\/[A-Za-z0-9_-]+\/(chat-threads(?:\/[A-Za-z0-9_-]+)?|evidence-bundles(?:\/[A-Za-z0-9_-]+(?:\/answer-approval)?)?|answer-jobs(?:\/[A-Za-z0-9_-]+)?|generation-jobs|investigation-plans\/jobs(?:\/[A-Za-z0-9_-]+)?(?:\?bundleId=[A-Za-z0-9_-]+)?)$/.test(path)
  return method === 'POST' && (['/workspaces', '/ai/providers/CODEX/connection'].includes(path)
    || /^\/workspaces\/[A-Za-z0-9_-]+\/(sources(?:\/[A-Za-z0-9_-]+\/sync)?|chat-threads|investigation-plans\/(preview|jobs)|evidence-bundles\/[A-Za-z0-9_-]+\/(answer-preview|answer-approval|answer-jobs))$/.test(path))
}
