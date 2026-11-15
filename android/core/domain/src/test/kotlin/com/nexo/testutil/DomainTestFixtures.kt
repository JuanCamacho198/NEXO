package com.nexo.testutil

/**
 * Domain-test fixture constants.
 *
 * [TEST_USER_ID] lives in :core:test (`AccountTestFixtures`) and is imported
 * from there.
 *
 * [TEST_BUILTIN_GUTENDEX] stays here rather than moving to :core:test: it is a
 * mirror of the production constant `com.nexo.data.remote.catalog.BUILTIN_GUTENDEX`,
 * which lives in :core:data. :core:domain must not depend on :core:data, so the
 * value is duplicated for exactly two domain test files. It is an opaque
 * identifier: no domain logic branches on its text, and no assertion compares
 * against the :core:data original.
 */

/** Built-in Gutendex provider id; mirrors `com.nexo.data.remote.catalog.BUILTIN_GUTENDEX`. */
const val TEST_BUILTIN_GUTENDEX: String = "builtin:gutendex"
