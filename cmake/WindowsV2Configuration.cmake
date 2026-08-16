function(chatroom_validate_windows_v2_configuration)
    if(CHATROOM_ENABLE_WINDOWS_V2_FORWARDING
            AND NOT CHATROOM_ENABLE_WINDOWS_V2_PREVIEW)
        message(FATAL_ERROR
            "Windows V2 forwarding requires the Windows V2 preview")
    endif()
    if(CHATROOM_ENABLE_WINDOWS_V2_SEARCH
            AND NOT CHATROOM_ENABLE_WINDOWS_V2_PREVIEW)
        message(FATAL_ERROR
            "Windows V2 search requires the Windows V2 preview")
    endif()
    if(CHATROOM_ENABLE_WINDOWS_V2_NOTIFICATIONS
            AND NOT CHATROOM_ENABLE_WINDOWS_V2_PREVIEW)
        message(FATAL_ERROR
            "Windows V2 notifications require the Windows V2 preview")
    endif()
    if(NOT CHATROOM_ENABLE_WINDOWS_V2_PREVIEW)
        if(NOT "${CHATROOM_WINDOWS_V2_WSS_URL}" STREQUAL "")
            message(FATAL_ERROR
                "CHATROOM_WINDOWS_V2_WSS_URL requires CHATROOM_ENABLE_WINDOWS_V2_PREVIEW=ON")
        endif()
        if(NOT "${CHATROOM_WINDOWS_V2_FALLBACK_WSS_URL}" STREQUAL "")
            message(FATAL_ERROR
                "CHATROOM_WINDOWS_V2_FALLBACK_WSS_URL requires CHATROOM_ENABLE_WINDOWS_V2_PREVIEW=ON")
        endif()
        return()
    endif()

    if(NOT CHATROOM_WINDOWS_V2_WSS_URL MATCHES
            "^wss://[A-Za-z0-9][A-Za-z0-9.:-]*/v2/windows$")
        message(FATAL_ERROR "Windows V2 endpoint must be an exact safe WSS literal")
    endif()
    if(CHATROOM_WINDOWS_V2_WSS_URL MATCHES "@|//v2|:::")
        message(FATAL_ERROR "Windows V2 endpoint contains an unsafe authority")
    endif()
    if(NOT "${CHATROOM_WINDOWS_V2_FALLBACK_WSS_URL}" STREQUAL "")
        if(NOT CHATROOM_WINDOWS_V2_FALLBACK_WSS_URL MATCHES
                "^wss://[A-Za-z0-9][A-Za-z0-9.:-]*/v2/windows$"
                OR CHATROOM_WINDOWS_V2_FALLBACK_WSS_URL MATCHES "@|//v2|:::"
                OR CHATROOM_WINDOWS_V2_FALLBACK_WSS_URL STREQUAL CHATROOM_WINDOWS_V2_WSS_URL)
            message(FATAL_ERROR "Windows V2 fallback endpoint must be a distinct exact safe WSS literal")
        endif()
    endif()
endfunction()

if(CMAKE_SCRIPT_MODE_FILE)
    chatroom_validate_windows_v2_configuration()
endif()
