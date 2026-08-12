function(chatroom_validate_windows_update_configuration)
    set(_configured_values
        CHATROOM_UPDATE_CHANNEL
        CHATROOM_UPDATE_MANIFEST_URL
        CHATROOM_UPDATE_PRIMARY_KEY_ID
        CHATROOM_UPDATE_PRIMARY_PUBLIC_KEY_HEX
        CHATROOM_UPDATE_SECONDARY_KEY_ID
        CHATROOM_UPDATE_SECONDARY_PUBLIC_KEY_HEX)

    if(NOT CHATROOM_ENABLE_WINDOWS_UPDATES)
        foreach(_name IN LISTS _configured_values)
            if(NOT "${${_name}}" STREQUAL "")
                message(FATAL_ERROR
                    "${_name} requires CHATROOM_ENABLE_WINDOWS_UPDATES=ON")
            endif()
        endforeach()
        return()
    endif()

    if(NOT CHATROOM_UPDATE_CHANNEL MATCHES "^(stable|beta)$")
        message(FATAL_ERROR "Windows update channel must be stable or beta")
    endif()
    if(NOT CHATROOM_UPDATE_MANIFEST_URL MATCHES
            "^https://[A-Za-z0-9][A-Za-z0-9._:/-]*/manifest\\.json$")
        message(FATAL_ERROR "Windows update manifest URL is not a safe HTTPS literal")
    endif()
    if(NOT CHATROOM_UPDATE_MANIFEST_URL MATCHES
            "/${CHATROOM_UPDATE_CHANNEL}/manifest\\.json$")
        message(FATAL_ERROR "Windows update manifest URL does not match its channel")
    endif()
    string(REGEX REPLACE "^https://" "" _manifest_authority_and_path
           "${CHATROOM_UPDATE_MANIFEST_URL}")
    if(_manifest_authority_and_path MATCHES "//")
        message(FATAL_ERROR "Windows update manifest URL contains an empty path segment")
    endif()

    foreach(_key_prefix IN ITEMS PRIMARY SECONDARY)
        set(_id_name "CHATROOM_UPDATE_${_key_prefix}_KEY_ID")
        set(_hex_name "CHATROOM_UPDATE_${_key_prefix}_PUBLIC_KEY_HEX")
        set(_id "${${_id_name}}")
        set(_hex "${${_hex_name}}")
        if(_key_prefix STREQUAL "SECONDARY" AND _id STREQUAL "" AND _hex STREQUAL "")
            continue()
        endif()
        string(LENGTH "${_id}" _id_length)
        string(LENGTH "${_hex}" _hex_length)
        if(_id_length LESS 1 OR _id_length GREATER 64
                OR NOT _id MATCHES "^[a-z0-9][a-z0-9.-]*$")
            message(FATAL_ERROR "Windows update key ID is invalid")
        endif()
        if(NOT _hex_length EQUAL 64 OR NOT _hex MATCHES "^[0-9a-f]+$")
            message(FATAL_ERROR "Windows update public key must be 32-byte lowercase hex")
        endif()
    endforeach()
endfunction()

if(CMAKE_SCRIPT_MODE_FILE)
    chatroom_validate_windows_update_configuration()
endif()
