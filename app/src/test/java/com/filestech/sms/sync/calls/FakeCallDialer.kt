package com.filestech.sms.sync.calls

/** A recording [CallDialer]: tests read back the dialed numbers and set the outcome. */
internal class FakeCallDialer(
    var outcome: DialOutcome = DialOutcome.Success,
) : CallDialer {
    val dialedNumbers = mutableListOf<String>()

    override suspend fun dial(number: String): DialOutcome {
        dialedNumbers += number
        return outcome
    }
}
