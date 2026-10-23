import { describe, expect, it } from 'vitest';
import { CircuitBreaker } from '../src/breaker';

function clock(start = 1_000_000) {
  let current = start;
  return {
    now: () => current,
    advance(ms: number) {
      current += ms;
    },
  };
}

describe('CircuitBreaker', () => {
  it('stays closed below the threshold', () => {
    const breaker = new CircuitBreaker({ now: clock().now });
    breaker.recordFailure('a');
    breaker.recordFailure('a');
    expect(breaker.canRequest('a')).toBe(true);
  });

  it('opens after three consecutive failures', () => {
    const breaker = new CircuitBreaker({ now: clock().now });
    breaker.recordFailure('a');
    breaker.recordFailure('a');
    breaker.recordFailure('a');
    expect(breaker.canRequest('a')).toBe(false);
  });

  it('resets the failure count on success', () => {
    const breaker = new CircuitBreaker({ now: clock().now });
    breaker.recordFailure('a');
    breaker.recordFailure('a');
    breaker.recordSuccess('a');
    breaker.recordFailure('a');
    breaker.recordFailure('a');
    expect(breaker.canRequest('a')).toBe(true);
  });

  it('half-opens after the cooldown and grants exactly one probe', () => {
    const time = clock();
    const breaker = new CircuitBreaker({ now: time.now });
    for (let i = 0; i < 3; i += 1) breaker.recordFailure('a');

    time.advance(299_999);
    expect(breaker.canRequest('a')).toBe(false);

    time.advance(1);
    expect(breaker.canRequest('a')).toBe(true);
    // One probe at a time: a second concurrent request stays out.
    expect(breaker.canRequest('a')).toBe(false);

    breaker.recordSuccess('a');
    expect(breaker.canRequest('a')).toBe(true);
  });

  it('reopens for a full cooldown when the probe fails', () => {
    const time = clock();
    const breaker = new CircuitBreaker({ now: time.now });
    for (let i = 0; i < 3; i += 1) breaker.recordFailure('a');

    time.advance(300_000);
    expect(breaker.canRequest('a')).toBe(true);
    breaker.recordFailure('a');

    time.advance(299_999);
    expect(breaker.canRequest('a')).toBe(false);
    time.advance(1);
    expect(breaker.canRequest('a')).toBe(true);
  });

  it('tracks each addon independently', () => {
    const breaker = new CircuitBreaker({ now: clock().now });
    for (let i = 0; i < 3; i += 1) breaker.recordFailure('a');
    expect(breaker.canRequest('a')).toBe(false);
    expect(breaker.canRequest('b')).toBe(true);
  });

  it('honors a custom threshold and cooldown', () => {
    const time = clock();
    const breaker = new CircuitBreaker({ threshold: 1, openMs: 5_000, now: time.now });
    breaker.recordFailure('a');
    expect(breaker.canRequest('a')).toBe(false);
    time.advance(5_000);
    expect(breaker.canRequest('a')).toBe(true);
  });
});
