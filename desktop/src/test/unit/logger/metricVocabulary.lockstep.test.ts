/**
 * Metric-vocabulary lockstep test: the TS vocabulary and the Kotlin mirror
 * (`android/app/src/main/java/com/nexo/debug/MetricVocabulary.kt`) must
 * stay identical. Any drift fails the build, same pattern as the CFI
 * lockstep test.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { SHARED_METRIC_VOCABULARY } from '$lib/shared/logger/metricTypes';

const KOTLIN_PATH = resolve(
  process.cwd(),
  '../android/app/src/main/java/com/nexo/debug/MetricVocabulary.kt',
);

describe('metricVocabulary lockstep', () => {
  it('Kotlin mirror exists', () => {
    expect(() => readFileSync(KOTLIN_PATH, 'utf-8')).not.toThrow();
  });

  it('TS vocabulary == Kotlin mirror', () => {
    const kotlin = readFileSync(KOTLIN_PATH, 'utf-8');
    const block = kotlin.match(/val P0_METRIC_NAMES\s*=\s*listOf\(([\s\S]*?)\)/);
    expect(block).not.toBeNull();
    const kotlinNames = (block![1] as string)
      .split(',')
      .map((line) => line.trim().replace(/"/g, ''))
      .filter((line) => line.length > 0);
    expect(kotlinNames).toEqual([...SHARED_METRIC_VOCABULARY]);
  });
});
