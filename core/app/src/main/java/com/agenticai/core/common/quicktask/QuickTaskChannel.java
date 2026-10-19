package com.agenticai.core.common.quicktask;

/**
 * The delivery channels Simple mode's Quick Task box offers - "on button
 * click it should get executed either through whatsapp, sms or on giving a
 * call." {@link #LOG} was added later ("there should be option to set the
 * output should be put to logs, not always call, whatsapp and sms") for
 * tasks the person just wants run and recorded - drafts, dry runs, anything
 * where nothing should actually go out to a phone. Unlike the other three,
 * LOG never touches {@code WhatsAppDispatchService}/{@code
 * SmsDispatchService}/{@code VoiceCallService} and doesn't require a phone
 * number on file - see {@code QuickTaskService#validate}/{@code #execute}.
 */
public enum QuickTaskChannel {
    WHATSAPP,
    SMS,
    CALL,
    LOG
}
