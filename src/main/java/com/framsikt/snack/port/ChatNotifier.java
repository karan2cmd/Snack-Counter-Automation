package com.framsikt.snack.port;

/** Outbound port to the company chat tool. */
public interface ChatNotifier {

    /** Private message to one employee. */
    void sendDirect(String employeeId, String message);

    /** Message in the office's snack channel, visible to all volunteers of that office. */
    void postToOffice(String officeId, String message);
}
