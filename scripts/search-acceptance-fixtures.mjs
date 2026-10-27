import { createHash } from 'node:crypto'
import { writeFileSync } from 'node:fs'

const document = (path, text, id = path) => ({ id, path, text })
const strategy = document('strategy/Player.java', 'package strategy;\npublic class Player {\n private Strategy strategy;\n public Player(Strategy strategy) { this.strategy = strategy; }\n public int play(int input) { return strategy.choose(input); }\n}')
const winning = document('strategy/WinningStrategy.java', 'package strategy;\npublic class WinningStrategy implements Strategy {\n private boolean won;\n private Hand previousHand;\n public Hand nextHand() {\n  if (!won) previousHand = Hand.getHand(random.nextInt(3));\n  return previousHand;\n }\n public void study(boolean win) { won = win; }\n}')
const policy = document('policy.txt', 'BLUE 배지는 72시간 후 만료됩니다. AMBER 배지는 24시간 후 만료됩니다. 자동 갱신은 없습니다.')
const operations = document('운영.md', '센서 값의 월평균 수기 보정은 120건입니다. 센서 장비의 분기 교체는 3건입니다.')
const agile = document('Agile.md', '빌드는 10분 이내로 끝내기를 권장합니다. 빌드가 길면 피드백이 늦어집니다.')
const royce = document('Agile.md', '폭포수 모델의 시초로 소개되는 윈스턴 로이스(Winston Royce)는 이전 단계로 돌아가는 피드백과 반복을 강조했습니다.\n' + '그 밖의 개발 활동은 팀 상황에 따라 정합니다.\n'.repeat(100) + '\n참고도서: 폭포수 모델의 역사, 워터폴 개발 방법, 워터폴 방식 안내, 소프트웨어 공학.')
const outbound = document('mapping.md', '입하 데이터는 inbound_order에 저장합니다. 출고 데이터는 outbound_order에 저장합니다.')
const state = document('state/NightState.java', 'public class NightState { public void doClock(Context context, int hour) { if (hour >= 9 && hour < 17) context.changeState(DayState.getInstance()); } }')
const singleton = document('singleton/Main.java', 'Singleton obj1 = Singleton.getInstance(); Singleton obj2 = Singleton.getInstance(); boolean same = obj1 == obj2;')
const definitions = [
  ['Strategy 예제', [strategy], ['strategy/Player.java'], ['strategy.choose']],
  ['AMBER 만료 시간은?', [policy], ['policy.txt'], ['AMBER', '24시간']],
  ['월평균 수기 보정은 몇 건?', [operations], ['운영.md'], ['수기 보정', '120건']],
  ['빌드 권장 시간은?', [agile], ['Agile.md'], ['10분 이내']],
  ['출고 데이터 테이블은?', [outbound], ['mapping.md'], ['출고', 'outbound_order']],
  ['선택 알고리즘을 외부에서 전달하는 코드는 어디야?', [strategy], ['strategy/Player.java'], ['Player(Strategy strategy)', 'this.strategy = strategy']],
  ['직전 판에서 이겼으면 같은 손을 다시 내는 코드는?', [winning], ['strategy/WinningStrategy.java'], ['if (!won)', 'return previousHand']],
  ['한 달에 사람이 직접 고치는 센서 값이 몇 건이야?', [operations], ['운영.md'], ['수기 보정', '120건']],
  ['워터폴 방식의 시초로 소개된 사람은 누구야?', [royce], ['Agile.md'], ['로이스', '피드백']],
  ['매번 한꺼번에 내보내는 대신 잘게 배포하자는 이유?', [document('deployment.md', '배포 단위를 작게 하면 실패했을 때 복구해야 하는 범위가 작아지고 재작업 낭비가 줄어듭니다.')], ['deployment.md'], ['복구', '낭비']],
  ['전략패턴예제좀', [strategy], ['strategy/Player.java'], ['strategy.choose']],
  ['DB에 입하 저장되는 곳', [document('receipt.md', 'Inbound receipts are persisted in the inbound_receipt table. Outbound shipments are stored in shipment.')], ['receipt.md'], ['Inbound receipts', 'inbound_receipt']],
  ['재고조정 API', [document('InventoryAdjustmentController.java', '@RestController\nclass InventoryAdjustmentController { @PostMapping("/inventory/adjustments") public void adjustInventory(Adjustment request) { inventoryService.adjust(request); } }')], ['InventoryAdjustmentController.java'], ['/inventory/adjustments', 'inventoryService.adjust']],
  ['WIP를 줄여야 하는 이유', [document('flow.md', 'WIP는 진행 중 업무(Work In Progress)입니다. 진행 중 업무 수를 제한하면 전환 비용과 대기 시간이 줄어듭니다.')], ['flow.md'], ['전환 비용', '대기 시간']],
  ['strategy 예제 어디있지?', [strategy, state, singleton], ['strategy/Player.java'], ['strategy.choose']],
  ['달 표면 중력?', [state, policy], [], []],
  ['피자 만드는 법?', [strategy], [], []],
  ['우리 팀 실패율?', [agile], [], [], 'ANSWER_GATE_REQUIRED'],
  ['A업무 월 건수?', [document('B업무.md', 'B업무 월 건수는 120건입니다.')], [], [], 'ANSWER_GATE_REQUIRED'],
  ['현재 정책?', [document('expired-policy.md', '2020년에 만료된 정책입니다. BLUE 배지는 72시간 후 만료됩니다.')], [], [], 'FRESHNESS_AND_ANSWER_GATE_REQUIRED'],
  ['AMBER 만료 조건과 기한은?', [document('boundary.md', '가상 운영 안내. '.repeat(62) + '\nAMBER 배지는 최초 발급 시점을 기준으로\n24시간 뒤 만료되며 자동 갱신되지 않습니다.')], ['boundary.md'], ['최초 발급', '24시간', '자동 갱신']],
  ['GREEN 품목의 교체 건수는?', [document('long.md', 'BLUE 품목 수기 보정은 120건입니다.\n' + '관련 없는 작업 일지입니다.\n'.repeat(1000) + '\nGREEN 품목 교체는 120건입니다.')], ['long.md'], ['GREEN', '교체', '120건']],
  ['전략 인터페이스 구현과 호출 관계는?', [document('strategy/Strategy.java', 'public interface Strategy { int choose(int input); }'), document('strategy/FixedStrategy.java', 'public class FixedStrategy implements Strategy { public int choose(int input) { return 7; } }'), strategy], ['strategy/Strategy.java', 'strategy/FixedStrategy.java', 'strategy/Player.java'], ['interface Strategy', 'implements Strategy', 'strategy.choose']],
  ['워터폴 방식의 시초로 소개된 사람은 누구야?', [royce], ['Agile.md'], ['로이스', '피드백']],
  ['재시도는 실제 몇 번 실행하나?', [document('Retry.java', '// 계획: 재시도 10번\nclass Retry { void run() { for (int attempt = 0; attempt < 3; attempt++) send(); } }')], ['Retry.java'], ['attempt < 3'], 'ANSWER_GATE_REQUIRED'],
  ['전략 패턴 사용 예제는?', [document('strategy/Main.java', 'Player player = new Player(new WinningStrategy()); player.nextHand();'), document('factory/Main.java', 'Factory factory = new IDCardFactory(); Product card = factory.create("sample");'), singleton], ['strategy/Main.java'], ['new WinningStrategy']],
  ['선택 알고리즘을 외부에서 전달하는 코드는 어디야?', [strategy], ['strategy/Player.java'], ['Player(Strategy strategy)'], 'FOUR_INGESTION_ROUTES_REQUIRED'],
  ['AMBER 만료 시간은?', [document('one/policy.txt', policy.text), document('two/policy.txt', 'BLUE 배지는 36시간 후 만료됩니다.')], ['one/policy.txt'], ['AMBER', '24시간']],
  ['BLUE 만료 시간은?', [document('policy.txt', 'BLUE 배지는 36시간 후 만료됩니다.')], ['policy.txt'], ['36시간'], 'IMMUTABLE_HISTORY_REQUIRED'],
  ['BLUE 만료 시간은?', [policy], [], [], 'DELETE_AND_IMMUTABLE_HISTORY_REQUIRED'],
]

export function searchAcceptanceFixtures() {
  return definitions.flatMap(([question, documents, paths, fragments, remaining = null], fixtureIndex) =>
    Array.from({ length: 5 }, (_, action) => {
      const fixture = { question, documents, paths, fragments, remaining }
      return { id: `T${String(601 + fixtureIndex * 5 + action).padStart(4, '0')}`, action,
        fixtureHash: createHash('sha256').update(JSON.stringify(fixture)).digest('hex'), ...fixture }
    }))
}

if (process.argv[1]?.endsWith('/search-acceptance-fixtures.mjs')) {
  const output = JSON.stringify(searchAcceptanceFixtures(), null, 2) + '\n'
  if (process.argv[2] === '--write') writeFileSync(new URL('../docs/fixtures/search-acceptance.json', import.meta.url), output)
  else process.stdout.write(output)
}
