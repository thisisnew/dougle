export class FakeEventSource {
  static instances: FakeEventSource[] = []
  closed = false
  onerror: (() => void) | null = null
  private listeners = new Map<string, (event: MessageEvent) => void>()
  constructor(public url: string) { FakeEventSource.instances.push(this) }
  addEventListener(name: string, listener: (event: MessageEvent) => void) { this.listeners.set(name, listener) }
  close() { this.closed = true }
  emit(name: string, value: unknown) { this.listeners.get(name)?.(new MessageEvent(name, { data: JSON.stringify(value) })) }
}
