// Production flag implementation
class FeatureFlagClient {
  private final FliptClient client;
  boolean enabled(String key) { return client.evaluate(key); }
}
