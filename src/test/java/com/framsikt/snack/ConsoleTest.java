package com.framsikt.snack;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleTest {

    private String runScript(String script) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        new Console(out).run(new BufferedReader(new StringReader(script)), false);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void full_walkthrough_via_typed_commands() throws IOException {
        String output = runScript(String.join("\n",
                "empty OSL someone",
                "accept R-1 lars",          // ingrid is offered first, lars may not take it
                "accept R-1 ingrid",
                "accept R-1 lars",          // already taken
                "buy R-1 ingrid 850 fruit",
                "hr HR-1 approve",
                "report",
                "quit"));

        assertThat(output)
                .contains("@ingrid: Hi Ingrid, the Oslo snack box is empty")
                .contains("REFUSED: Refill R-1 is offered to someone else")
                .contains("#snacks-OSL: Ingrid is refilling the snack box")
                .contains("REFUSED: Refill R-1 is already taken by ingrid")
                .contains("claim HR-1 for 850.00 NOK has been filed")
                .contains("=> R-1 OSL REIMBURSED")
                .contains("TOTAL (both offices): 850.00 NOK");
    }

    @Test
    void wait_triggers_handover_and_bad_input_is_explained() throws IOException {
        String output = runScript(String.join("\n",
                "empty OSL someone",
                "wait 4h",
                "status",
                "accept",
                "dance",
                "wait 4x"));

        assertThat(output)
                .contains("@ingrid: No worries - the snack refill has been passed to someone else.")
                .contains("R-1 OSL OFFERED holder=lars")
                .contains("Usage: accept <refillId> <volunteer>")
                .contains("Unknown command 'dance'")
                .contains("REFUSED: Use e.g. 30m, 4h or 2d");
    }
}
