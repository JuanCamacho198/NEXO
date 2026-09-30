package com.nexo.benchmark

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Normalizes AndroidX macrobenchmark results into the single metrics document the
 * regression gate consumes.
 *
 * `:benchmark:connectedBenchmarkAndroidTest` writes one `<Class>-benchmarkData.json`
 * per measured class into the instrumentation "additional test output" directory
 * (the same directory AGP pulls back to the host). [export] reads every such file
 * and rewrites `<output>/benchmarkMetrics/metrics-current.json` with stable keys of
 * the form `<file>.<path>.<metric>.p95` — the shape `:benchmark:verifyBenchmarkRegression`
 * and `baseline/metrics-baseline.json` match by suffix.
 *
 * Merge-not-overwrite: JUnit orders journey classes non-deterministically and each
 * class exports itself in `@AfterClass`, so an export must never drop metrics a
 * previously exported class already recorded.
 *
 * SCOPE HONESTY: this is a device-only path and could NOT be executed in the S9
 * slice (no emulator available). It is deliberately tolerant of both JSON shapes
 * the library has shipped — a `benchmarks[]`/`metrics{}` tree and a flat
 * `metrics{}`/`results{}` object — by walking the parsed document for any nested
 * object that carries a numeric `p95` (falling back to `median`) instead of
 * hardcoding one schema.
 */
internal object BenchmarkMetricsExporter {
    private const val TAG = "BenchmarkMetrics"
    private const val OUTPUT_RELATIVE_PATH = "benchmarkMetrics/metrics-current.json"
    private const val ADDITIONAL_TEST_OUTPUT_DIR_ARG = "additionalTestOutputDir"
    private const val BENCHMARK_DATA_SUFFIX = "-benchmarkData.json"
    private const val P95 = "p95"
    private const val MEDIAN = "median"
    private const val SCHEMA_VERSION = 1

    /** Merges all benchmarkData files into the normalized metrics document. Returns `null` when no output dir exists. */
    fun export(): File? {
        val outputDirectory = outputDirectory() ?: return null
        val outputFile = File(outputDirectory, OUTPUT_RELATIVE_PATH)

        val metrics = LinkedHashMap<String, Double>()
        metrics.putAll(readOwnMetrics(outputFile))
        outputDirectory
            .listFiles { file -> file.isFile && file.name.endsWith(BENCHMARK_DATA_SUFFIX) }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                collectNumericStats(
                    node = readJson(file),
                    path = file.name.removeSuffix(BENCHMARK_DATA_SUFFIX),
                    into = metrics,
                )
            }

        outputFile.parentFile?.mkdirs()
        outputFile.writeText(render(metrics))
        Log.i(TAG, "exported ${metrics.size} metric(s) to ${outputFile.absolutePath}")
        return outputFile
    }

    /** Re-reads a previously exported document so a later export cannot lose earlier metrics. */
    private fun readOwnMetrics(file: File): Map<String, Double> {
        if (!file.isFile) return emptyMap()
        val metrics = readJson(file).optJSONObject("metrics") ?: return emptyMap()
        return metrics
            .keys()
            .asSequence()
            .mapNotNull { key -> metrics.optDouble(key).takeIf { !it.isNaN() }?.let { key to it } }
            .toMap()
    }

    /** Records `<path>.<metric>.p95` for every nested object that carries a numeric p95/median. */
    private fun collectNumericStats(
        node: JSONObject,
        path: String,
        into: MutableMap<String, Double>,
    ) {
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = node.opt(key)) {
                is JSONObject -> {
                    val percentile =
                        value.optDouble(P95).takeIf { !it.isNaN() }
                            ?: value.optDouble(MEDIAN).takeIf { !it.isNaN() }
                    if (percentile != null) {
                        into["$path.$key.$P95"] = percentile
                    } else {
                        collectNumericStats(value, "$path.$key", into)
                    }
                }

                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        (value.opt(index) as? JSONObject)?.let {
                            collectNumericStats(it, "$path.$key[$index]", into)
                        }
                    }
                }

                else -> Unit
            }
        }
    }

    private fun render(metrics: Map<String, Double>): String {
        val values = JSONObject()
        metrics.toSortedMap().forEach { (key, value) -> values.put(key, value) }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("placeholder", false)
            .put("metrics", values)
            .toString(2)
    }

    private fun outputDirectory(): File? {
        val configured = InstrumentationRegistry.getArguments().getString(ADDITIONAL_TEST_OUTPUT_DIR_ARG)
        val directory =
            if (configured.isNullOrBlank()) {
                InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            } else {
                File(configured)
            }
        return directory?.also { it.mkdirs() }
    }

    private fun readJson(file: File): JSONObject =
        try {
            JSONObject(file.readText())
        } catch (error: Exception) {
            Log.w(TAG, "unreadable benchmark JSON: ${file.name}", error)
            JSONObject()
        }
}
