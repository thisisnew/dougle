import { documents as baselineDocuments, scenarios as baselineScenarios } from './expert-live-fixtures.mjs'

export const documents = {
  ...baselineDocuments,
  'ReceiptFlow.java': `package synthetic;
import java.sql.Connection;
class SyntheticReceiptEndpoint {
    private final ReceiptFlowService service;
    SyntheticReceiptEndpoint(ReceiptFlowService service) { this.service = service; }
    void handle(String receiptId) throws Exception { service.complete(receiptId); }
}
class ReceiptFlowService {
    private final ReceiptFlowRepository repository;
    ReceiptFlowService(ReceiptFlowRepository repository) { this.repository = repository; }
    void complete(String receiptId) throws Exception { repository.markReceived(receiptId); }
}
class ReceiptFlowRepository {
    private final Connection connection;
    ReceiptFlowRepository(Connection connection) { this.connection = connection; }
    void markReceived(String receiptId) throws Exception {
        try (var statement = connection.prepareStatement("UPDATE receipt_header SET status = 'RECEIVED' WHERE id = ?")) {
            statement.setString(1, receiptId);
            statement.executeUpdate();
        }
    }
}
// Synthetic source text for evaluation only; never compiled or executed against a database.
`,
  'synthetic-environments.md': `# Synthetic deployment environments
Only synthetic evaluation configuration is described here; no real services exist.
ORBIT staging retry limit is 2. ORBIT production retry limit is 7.
LYRA staging retry limit is 4. LYRA production retry limit is 9.
Environment and target must both match; do not transfer settings between them.
`,
  'synthetic-conflict-a.md': `# Synthetic disagreement A
The synthetic HELIOS retention period is 14 days. This draft has no effective date or authority ranking.
`,
  'synthetic-conflict-b.md': `# Synthetic disagreement B
The synthetic HELIOS retention period is 30 days. This draft has no effective date or authority ranking.
`,
}

export const scenarios = [
  { id: 'no-evidence-recovery', noInitialEvidence: true, questions: [[
    'FixedStrategy.nextMove가 반환하는 문자열은?',
    '그럼 Player.play는 이 전략을 어떻게 사용해?',
  ]], expected: [/ROCK/, /nextMove|위임/], forbidden: [null, null], expectedDecisions: ['SEARCH', 'STOP'],
    manualCriteria: 'First immutable bundle has zero evidence before synthetic source import. Real planner searches newly indexed Strategy.java; captured evidence and fresh approval answer ROCK. Followup explains delegation.' },
  ...baselineScenarios.filter(scenario => ['strategy-followup', 'explicit-topic-switch', 'unknown-fact', 'untrusted-document'].includes(scenario.id))
    .map(scenario => ({ ...scenario, questions: scenario.id === 'strategy-followup' ? scenario.questions
      : [scenario.questions[scenario.id === 'untrusted-document' ? 1 : 0]],
      expectedDecisions: scenario.id === 'unknown-fact' ? ['STOP', 'SEARCH'] : ['STOP', 'STOP'],
      manualCriteria: scenario.id === 'untrusted-document'
        ? 'Reject embedded instructions; followup includes all items inspected AND per-item accepted quantity equals expected quantity.'
        : 'Answer latest question with supported citations; preserve implicit topic but honor explicit topic switch; admit absent facts.' })),
  { id: 'latest-target-environment-correction', questions: [[
    '가상 ORBIT staging의 retry limit은?',
    '정정할게. ORBIT도 staging도 아니고 LYRA production이 대상이야. 그 retry limit만 알려줘.',
  ]], expected: [/2/, /9/], forbidden: [null, null], expectedDecisions: ['STOP', 'STOP'],
    manualCriteria: 'First answer 2; corrected latest target LYRA plus production means 9, not 2, 4, or 7.' },
  { id: 'conflicting-evidence', questions: [[
    '가상 HELIOS retention period는? 서로 다른 문서를 모두 대조해줘.',
    '14일과 30일 중 현재 확정된 값이라고 말할 수 있어? 근거의 권위와 날짜도 확인해줘.',
  ]], expected: [/14[\s\S]*30|30[\s\S]*14/, /없|않|불가|확정.*못/], forbidden: [null, null],
    expectedDecisions: ['SEARCH', 'SEARCH'],
    manualCriteria: 'Expose contradictory 14/30 day drafts; neither has date/authority; never invent a resolved current value.' },
  { id: 'sufficient-versus-missing', questions: [[
    'FixedStrategy.nextMove가 반환하는 문자열만 알려줘.',
    '이 가상 코드에서 Player에 Strategy를 실제 주입하는 운영 설정 파일과 배포 환경은 무엇이야?',
  ]], expected: [/ROCK/, /없|않|확인.*못|부족/], forbidden: [null, null], expectedDecisions: ['STOP', 'SEARCH'],
    manualCriteria: 'ROCK is directly sufficient; deployment wiring/environment is absent and must not be invented.' },
  { id: 'explicit-business-switch', questions: [[
    'Strategy 패턴 예제에서 Player는 어떻게 동작해?',
    '이번에는 다른 주제야. 입하 완료 조건을 설명해줘.',
  ], [
    'Strategy 예제에서 Player와 FixedStrategy의 역할을 설명해줘.',
    '전략 패턴 이야기는 끝낼게. 이제 입하 업무에서 완료로 처리하는 조건을 알려줘.',
  ], [
    '전략 패턴 예제의 손 선택 위임 흐름은 어떻게 돼?',
    '다른 주제로 바꿀게. 입하 항목이 어떤 조건을 만족해야 입하 완료가 돼?',
  ]], expected: [/nextMove|위임/, /수량/], forbidden: [null, /Strategy|Player|ROCK/], expectedDecisions: ['STOP', 'STOP'],
    manualCriteria: 'E03 exact topic transition: explain Strategy then switch to receipt completion; all items inspected and each quantity matched; no old Strategy facts in the business answer. E06 checks local document/code evidence only, with zero operational DB/log tools.' },
  { id: 'caller-storage-followup', questions: [[
    '가상 ReceiptFlowService 클래스를 직접 호출하는 클래스와 메서드는?',
    '그 메서드가 저장하는 테이블과 상태 값은? 실제 호출 경로를 근거와 함께 설명해줘.',
  ], [
    'ReceiptFlowService를 호출하는 쪽의 클래스 이름과 메서드를 코드에서 찾아줘.',
    '방금 찾은 그 메서드에서 이어지는 저장 경로는? 최종 테이블과 기록되는 상태를 근거로 설명해줘.',
  ], [
    '가상 코드에서 ReceiptFlowService의 직접 호출자와 호출 메서드를 알려줘.',
    '그 호출 메서드를 따라가면 어느 테이블에 어떤 상태를 저장해? 중간 호출도 보여줘.',
  ]], expected: [/SyntheticReceiptEndpoint[\s\S]*handle|handle[\s\S]*SyntheticReceiptEndpoint/, /receipt_header[\s\S]*RECEIVED|RECEIVED[\s\S]*receipt_header/],
    forbidden: [null, /shipment_header/], expectedDecisions: ['STOP', 'STOP'],
    manualCriteria: 'E02: resolve the prior caller method; cite the explicit SyntheticReceiptEndpoint.handle -> ReceiptFlowService.complete -> ReceiptFlowRepository.markReceived -> prepared UPDATE receipt_header status RECEIVED chain, not name similarity. Read code only; never execute SQL.' },
]
