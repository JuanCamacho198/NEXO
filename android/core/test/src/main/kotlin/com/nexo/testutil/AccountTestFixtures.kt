package com.nexo.testutil

/**
 * The account id every user-scoped fake is keyed to in tests.
 *
 * Rows written without an explicit owner land here, so a test that exercises a
 * single account needs no changes beyond passing [TEST_USER_ID] to the
 * repository/DAO call.
 */
const val TEST_USER_ID: String = "test-user"

/** A second account, for the isolation assertions the account-scoping change requires. */
const val OTHER_USER_ID: String = "other-user"

/**
 * Builds an [com.nexo.domain.session.ActiveUserProvider] already scoped to
 * [userId] so a ViewModel under test reads the account it expects.
 */
fun activeUserProviderFor(userId: String? = TEST_USER_ID): com.nexo.domain.session.ActiveUserProvider =
    com.nexo.domain.session
        .ActiveUserProvider()
        .apply { set(userId) }
