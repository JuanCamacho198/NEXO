package com.nexo.testutil

/**
 * Domain-test fixture constants.
 *
 * Mirrors the values these tests used from `:app`'s test sources
 * (`com.nexo.testutil.TEST_USER_ID`, `com.nexo.data.remote.catalog.BUILTIN_GUTENDEX`).
 * `:core:domain` must not depend on `:app`, so the constants are duplicated here.
 * Both are opaque identifiers: no domain logic branches on their text, and no
 * assertion compares against the `:app` originals.
 */
const val TEST_USER_ID: String = "test-user"

/** Built-in Gutendex provider id; mirrors `com.nexo.data.remote.catalog.BUILTIN_GUTENDEX`. */
const val TEST_BUILTIN_GUTENDEX: String = "builtin:gutendex"
