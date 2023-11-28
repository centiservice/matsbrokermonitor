// For "synthetic links" to not do anything
function matsbm_noclick(event) {
    event.preventDefault();
    return false;
}

/**
 * NOTICE: If you need to change the JSON Path, i.e. the path which this GUI employs to do "active" operations,
 * you can do so by setting the JS global variable "matsbm_json_path" when outputting the HTML, overriding the default
 * which is to use the current URL path (i.e. the same as the GUI is served on). They may be on the same path since
 * the HTML is served using GET, while the JSON uses PUT and DELETE with header "Content-Type: application/json".
 *
 * @returns {string} the JSON Path to use for all active JSON calls, employing PUT and DELETE, with header
 * "Content-Type: application/json".
 */
function matsbm_jsonpath() {
    return window.matsbm_json_path ? window.matsbm_json_path : window.location.pathname;
}

// Global key listener, dispatching to relevant "sub-listener" based on active view.
document.addEventListener('keydown', (event) => {
    // ?: We only care about the "pure" letters, not with any modifiers (e.g. Ctrl+R shall be reload, not reissue!)
    if (event.ctrlKey || event.altKey || event.metaKey) {
        // -> Modifiers present, ignore
        console.log("Modifiers present. Ignoring keypress.");
        return;
    }

    if (document.getElementById("matsbm_page_broker_overview")) {
        matsbm_brokerOverviewKeyListener(event);
    } else if (document.getElementById("matsbm_page_browse_queue")) {
        matsbm_browseQueueKeyListener(event);
    } else if (document.getElementById("matsbm_page_examine_message")) {
        if (matsbm_is_call_modal_active()) {
            matsbm_modalActiveKeyListener(event);
        } else {
            matsbm_examineMessageKeyListener(event);
        }
    }
}, false);


// Common force update button for Broker Overview and Browse Queue
function matsbm_button_forceupdate(event) {
    console.log("Force update")
    document.getElementById('matsbm_button_forceupdate').classList.add('matsbm_button_disabled');

    let actionMessage = document.getElementById('matsbm_action_message');
    actionMessage.textContent = "Updating..";

    let requestBody = {
        action: 'update'
    };

    let jsonPath = matsbm_jsonpath();

    fetch(jsonPath, {
        method: 'PUT', headers: {
            'Content-Type': 'application/json'
        }, body: JSON.stringify(requestBody)
    }).then(response => {
        if (!response.ok) {
            matsbm_fetch_response_not_ok_message(response);
            return;
        }
        response.json().then(result => {
            console.log(result);
            let actionMessage = document.getElementById('matsbm_action_message');
            if (result.resultOk) {
                actionMessage.textContent = "Updated! Time taken: " + result.timeTakenMillis + " ms";
                setTimeout(() => {
                    window.location.reload();
                }, 300)
            } else {
                actionMessage.textContent = "Not updated within timeout! Time taken: " + result.timeTakenMillis + " ms";
                actionMessage.classList.add('matsbm_action_error');
                setTimeout(() => {
                    window.location.reload();
                }, 3000)
            }
        }).catch(error => {
            matsbm_json_parse_error_message(error);
        });
    }).catch(error => {
        matsbm_fetch_error_message(error);
    });
}

function matsbm_fetch_response_not_ok_message(response) {
    console.error("MATS BROKER MONITOR: Response not OK", response);
    let actionMessage = document.getElementById('matsbm_action_message');
    actionMessage.textContent = "Error! HTTP Status: " + response.status + ": " + response.statusText;
    actionMessage.classList.add('matsbm_action_error');
}

function matsbm_json_parse_error_message(error) {
    console.error("MATS BROKER MONITOR: JSON error", error);
    let actionMessage = document.getElementById('matsbm_action_message');
    actionMessage.textContent = "JSON Error! " + error;
    actionMessage.classList.add('matsbm_action_error');
}

function matsbm_fetch_error_message(error) {
    console.error("MATS BROKER MONITOR: Fetch error", error);
    let actionMessage = document.getElementById('matsbm_action_message');
    actionMessage.textContent = "Fetch Error! " + error;
    actionMessage.classList.add('matsbm_action_error');
}

function matsbm_hide(elemId) {
    if (document.getElementById(elemId)) {
        document.getElementById(elemId).classList.add(
            elemId.includes("limit_div") ? "matsbm_input_limit_div_hidden" : "matsbm_button_hidden");
    }
}

function matsbm_show(elemId) {
    if (document.getElementById(elemId)) {
        document.getElementById(elemId).classList.remove(
            elemId.includes("limit_div") ? "matsbm_input_limit_div_hidden" : "matsbm_button_hidden");
    }
}

function matsbm_disable(buttonId) {
    if (document.getElementById(buttonId)) {
        document.getElementById(buttonId).classList.add("matsbm_button_disabled");
    }
}

function matsbm_enable(buttonId) {
    if (document.getElementById(buttonId)) {
        document.getElementById(buttonId).classList.remove("matsbm_button_disabled");
    }
}

function matsbm_click(buttonId) {
    if (!document.getElementById(buttonId).classList.contains('matsbm_button_hidden')) {
        document.getElementById(buttonId).click();
    }
}

function matsbm_is_button_enabled(buttonId) {
    return !document.getElementById(buttonId).classList.contains('matsbm_button_disabled');
}

// ::: BROKER OVERVIEW

// Key Listener for Broker Overview
function matsbm_brokerOverviewKeyListener(event) {
    const name = event.key;
    if (name === "Escape" || name === "u") {
        matsbm_click("matsbm_button_forceupdate");
    }

    if (name === "a") {
        matsbm_click("matsbm_button_show_all");
    }
    if (name === "b") {
        matsbm_click("matsbm_button_show_bad");
    }
}

function matsbm_button_show_all_destinations(event) {
    window.location = window.location.pathname + "?show=all";

    document.getElementById("matsbm_button_show_all").classList.add('matsbm_button_active')
    document.getElementById("matsbm_button_show_bad").classList.remove('matsbm_button_active')
}

function matsbm_button_show_bad_destinations(event) {
    window.location = window.location.pathname + "?show=bad";

    document.getElementById("matsbm_button_show_all").classList.remove('matsbm_button_active')
    document.getElementById("matsbm_button_show_bad").classList.add('matsbm_button_active')
}


// ::: BROWSE QUEUE

// Key Listener for Browse Queue
function matsbm_browseQueueKeyListener(event) {
    const name = event.key;
    if (name === "Escape") {
        // ?: Is the "Confirm Delete" button active (non-hidden)?
        if (matsbm_queueview_is_reissue_or_delete_confirm_active()) {
            // -> Yes it is active - then it is this we'll escape
            matsbm_delete_or_reissue_cancel();
        } else {
            setTimeout(() => {
                document.getElementById("matsbm_back_broker_overview").click();
            }, 10);
        }
        return;
    }

    // ?: Is active element an input text field? (i.e. "limit messages")
    var activeElement = document.activeElement;
    if (activeElement && activeElement.tagName === 'INPUT' && activeElement.type === 'text') {
        return;
    }

    if (name === "u") {
        matsbm_click("matsbm_button_forceupdate");
    }
    if (name === "r") {
        matsbm_click("matsbm_reissue_selected");
    }
    if (name === "R") {
        matsbm_click("matsbm_reissue_all");
    }
    if (name === "d") {
        matsbm_click("matsbm_delete_selected");
    }
    if (name === "D") {
        matsbm_click("matsbm_delete_all");
    }
    if (name === "x") {
        matsbm_click("matsbm_delete_selected_confirm");
        matsbm_click("matsbm_reissue_all_confirm");
        matsbm_click("matsbm_delete_all_confirm");
    }
}

function matsbm_queueview_is_reissue_or_delete_confirm_active() {
    let allDeleteConfirmButtonsHidden =
        document.getElementById("matsbm_delete_selected_confirm").classList.contains("matsbm_button_hidden")
        && document.getElementById("matsbm_delete_all_confirm").classList.contains("matsbm_button_hidden")
        && (!document.getElementById("matsbm_reissue_all_confirm")
            || document.getElementById("matsbm_reissue_all_confirm").classList.contains("matsbm_button_hidden"));

    return !allDeleteConfirmButtonsHidden;
}

function matsbm_queueview_hide_all_normal_buttons() {
    matsbm_hide("matsbm_reissue_selected");
    matsbm_hide("matsbm_delete_selected");
    matsbm_hide("matsbm_reissue_all");
    matsbm_hide("matsbm_delete_all");
    matsbm_hide("matsbm_button_forceupdate");
}

function matsbm_queueview_hide_rest() {
    matsbm_hide("matsbm_delete_selected_cancel");
    matsbm_hide("matsbm_delete_selected_confirm");
    matsbm_hide("matsbm_delete_all_cancel");
    matsbm_hide("matsbm_delete_all_confirm");
    matsbm_hide("matsbm_reissue_all_cancel");
    matsbm_hide("matsbm_reissue_all_confirm");

    matsbm_hide("matsbm_all_limit_div");
}

function matsbm_queueview_show_all_normal_buttons_hide_rest() {
    matsbm_show("matsbm_reissue_selected");
    matsbm_show("matsbm_delete_selected");
    matsbm_show("matsbm_reissue_all");
    matsbm_show("matsbm_delete_all");
    matsbm_show("matsbm_button_forceupdate");

    matsbm_queueview_hide_rest();
}

// :: COMMON CANCEL for REISSUE and DELETE

function matsbm_delete_or_reissue_cancel(event) {
    console.log("Cancel Reissue/Delete");
    matsbm_queueview_show_all_normal_buttons_hide_rest();
}

// :: REISSUE SELECTED (immediate, no confirm)

function matsbm_reissue_selected(event, queueId) {
    if (!matsbm_is_button_enabled("matsbm_reissue_selected")) {
        return;
    }
    console.log("Execute Reissue Selected");
    matsbm_queueview_hide_all_normal_buttons();
    // Invoke after a small delay, so that animation of hiding buttons is well underway.
    setTimeout(() => {
        matsbm_reissue_or_delete_selected(event, queueId, "reissue")
    }, 500);
}

// :: DELETE SELECTED

function matsbm_delete_selected_propose(event) {
    if (!matsbm_is_button_enabled("matsbm_delete_selected")) {
        return;
    }
    console.log("Propose Delete Selected");
    matsbm_queueview_hide_all_normal_buttons();

    // Show Delete Confirm and Delete Cancel
    matsbm_show("matsbm_delete_selected_confirm");
    matsbm_show("matsbm_delete_selected_cancel");
}

// Action for the "Confirm Delete" button made visible by clicking "Delete".
function matsbm_delete_selected_confirm(event, queueId) {
    console.log("Execute Delete Selected");
    matsbm_reissue_or_delete_selected(event, queueId, "delete")
}

// :: REISSUE ALL

function matsbm_reissue_all_propose(event) {
    if (!matsbm_is_button_enabled("matsbm_reissue_all")) {
        return;
    }
    console.log("Propose Reissue All");
    matsbm_queueview_hide_all_normal_buttons();

    // Show Delete Confirm and Delete Cancel
    matsbm_show("matsbm_reissue_all_cancel");
    matsbm_show("matsbm_reissue_all_confirm");
    matsbm_show("matsbm_all_limit_div")
}

function matsbm_reissue_all_confirm(event, queueId) {
    console.log("Execute Reissue All");
    const limitMessages = document.getElementById("matsbm_all_limit_messages").value;

    matsbm_queueview_hide_rest();
    // Invoke after a small delay, so that animation of hiding buttons is well underway.
    setTimeout(() => {
        matsbm_reissue_or_delete_all(event, queueId, "reissue", limitMessages)
    }, 500);
}

// :: DELETE ALL

function matsbm_delete_all_propose(event) {
    if (!matsbm_is_button_enabled("matsbm_delete_all")) {
        return;
    }
    console.log("Propose Delete all");
    matsbm_queueview_hide_all_normal_buttons();

    // Show Delete Confirm and Delete Cancel
    matsbm_show("matsbm_delete_all_cancel");
    matsbm_show("matsbm_delete_all_confirm");
    matsbm_show("matsbm_all_limit_div")
}

function matsbm_delete_all_confirm(event, queueId) {
    console.log("Execute Delete all");
    const limitMessages = document.getElementById("matsbm_all_limit_messages").value;
    matsbm_reissue_or_delete_all(event, queueId, "delete", limitMessages)
}

// :: CHECKBOXES

function matsbm_checkall(event) {
    for (const checkbox of document.body.querySelectorAll(".matsbm_checkmsg")) {
        checkbox.checked = event.target.checked;
    }
    matsbm_evaluate_checkall_and_buttons();
}

function matsbm_checkmsg(event) {
    matsbm_evaluate_checkall_and_buttons();
}

function matsbm_checkinvert(event) {
    for (const checkbox of document.body.querySelectorAll(".matsbm_checkmsg")) {
        checkbox.checked = !checkbox.checked;
    }
    matsbm_evaluate_checkall_and_buttons();
}

function matsbm_evaluate_checkall_and_buttons() {
    // :: Handle "check all" based on whether checkboxes are checked.
    let numchecked = 0;
    let numunchecked = 0;
    let allchecked = true;
    let allunchecked = true;
    for (const checkbox of document.body.querySelectorAll(".matsbm_checkmsg")) {
        if (checkbox.checked) {
            // -> checked
            numchecked++;
            allunchecked = false;
        } else {
            // -> unchecked
            numunchecked++;
            allchecked = false;
        }
    }
    const checkall = document.getElementById('matsbm_checkall');

    // ?? Handle all checked, none checked, or anything between
    if (numchecked + numunchecked === 0) {
        // -> There are no messages!
        checkall.indeterminate = false;
        checkall.checked = false;
    } else if (numchecked && numunchecked) {
        // -> Some of both
        checkall.indeterminate = true;
        checkall.checked = false;
    } else if (allchecked) {
        // -> All checked
        checkall.indeterminate = false;
        checkall.checked = true;
    } else if (allunchecked) {
        // -> All unchecked
        checkall.indeterminate = false;
        checkall.checked = false;
    }

    // We're changing selection: Cancel the "Confirm Delete" if it was active.
    matsbm_delete_or_reissue_cancel();

    // ?: Activate or deactivate Reissue/Delete based on whether any is selected.
    if (numchecked) {
        // -> Yes, checked, so enable "selected" buttons, and disable "all" buttons.
        matsbm_enable('matsbm_reissue_selected');
        matsbm_enable('matsbm_delete_selected');
        matsbm_disable('matsbm_reissue_all');
        matsbm_disable('matsbm_delete_all');
    } else {
        // -> No, not checked, so disable "selected" buttons, and enable "all" buttons.
        matsbm_disable('matsbm_reissue_selected');
        matsbm_disable('matsbm_delete_selected');
        // ?: Are there messages on the queue at all?
        if (matsbm_number_of_messages_on_queue) {
            matsbm_enable('matsbm_reissue_all');
            matsbm_enable('matsbm_delete_all');
        } else {
            matsbm_disable('matsbm_reissue_all');
            matsbm_disable('matsbm_delete_all');
        }
    }

    // Update selection text
    let selectionText = "Messages in list: " + (numchecked + numunchecked);
    if (allunchecked) {
        selectionText += ", no selected messages";
    } else if (allchecked) {
        selectionText += ", ALL messages selected";
    } else {
        selectionText += ". Selected: " + numchecked + ", not selected:" + numunchecked;
    }
    document.getElementById("matsbm_num_messages_shown").textContent = selectionText;
}

function matsbm_reissue_or_delete_selected(event, queueId, action) {
    matsbm_queueview_hide_rest();

    // :: Find which messages
    const msgSysMsgIds = [];
    for (const checkbox of document.body.querySelectorAll(".matsbm_checkmsg")) {
        if (checkbox.checked) {
            msgSysMsgIds.push(checkbox.getAttribute("data-msgid"));
        }
    }
    const actionPresent = action === "reissue" ? "Reissuing" : "Deleting";
    const actionPast = action === "reissue" ? "reissued" : "deleted";
    const operation = action === "reissue" ? "PUT" : "DELETE";

    console.log(actionPresent + " [" + msgSysMsgIds.length + "] selected message(s)...")

    document.getElementById('matsbm_action_message').textContent = actionPresent + " " + msgSysMsgIds.length + " message" + (msgSysMsgIds.length > 1 ? "s" : "") + ".";

    let jsonPath = matsbm_jsonpath();
    let requestBody = {
        action: action === "reissue" ? "reissue_selected" : "delete_selected",
        queueId: queueId,
        msgSysMsgIds: msgSysMsgIds
    };
    fetch(jsonPath, {
        method: operation, headers: {
            'Content-Type': 'application/json'
        }, body: JSON.stringify(requestBody)
    }).then(response => {
        if (!response.ok) {
            matsbm_fetch_response_not_ok_message(response);
            return;
        }
        response.json().then(result => {
            console.log(result);
            let actionMessage = document.getElementById('matsbm_action_message');
            actionMessage.innerHTML = "Done, " + result.numberOfAffectedMessages
                + " message" + (result.numberOfAffectedMessages > 1 ? "s" : "")
                + " " + actionPast
                + ". Time taken: " + result.timeTakenMillis + " ms."
                + (action === 'reissue' ? " <b>[Check console or log for new message ids!]</b>" : "");
            actionMessage.classList.add(action === "reissue" ? 'matsbm_action_reissued' : 'matsbm_action_deleted')
            for (const msgSysMsgId of Object.keys(result.affectedMessages)) {
                const row = document.getElementById('matsbm_msgid_' + msgSysMsgId);
                if (row) {
                    row.classList.add('matsbm_' + actionPast);
                } else {
                    console.error("Couldn't find message row for msgSysMsgId [" + msgSysMsgId + "].");
                }
            }

            console.log("Done, " + actionPast + " [" + result.numberOfAffectedMessages + "] messages(s):");
            let count = 0;
            for (const messageMeta of Object.values(result.affectedMessages)) {
                console.log("  " + count + ":" + JSON.stringify(messageMeta));
                count++;
            }
            // Annoying CSS "delayed transition" also somehow "overwrites" the row color transition..?!
            // Using JS hack instead, to delay second part of transition.
            setTimeout(() => {
                for (const [msgSysMsgId, messageMeta] of Object.entries(result.affectedMessages)) {
                    const row = document.getElementById('matsbm_msgid_' + msgSysMsgId);
                    if (row) {
                        row.classList.add('matsbs_delete_or_reissue');
                    }
                }
                setTimeout(() => window.location.reload(), action === "reissue" ? 2000 : 1200);
            }, 1500)
        }).catch(error => {
            matsbm_json_parse_error_message(error);
        });
    }).catch(error => {
        matsbm_fetch_error_message(error);
    });
}


function matsbm_reissue_or_delete_all(event, queueId, action, limitMessages) {
    console.log("Delete all, limitMessages:", limitMessages);

    matsbm_queueview_hide_rest();

    const actionPresent = action === "reissue" ? "Reissuing" : "Deleting";
    const actionPast = action === "reissue" ? "reissued" : "deleted";
    const operation = action === "reissue" ? "PUT" : "DELETE";

    console.log(actionPresent + " up to [" + limitMessages + "] message(s)...")

    document.getElementById('matsbm_action_message').textContent = actionPresent + (limitMessages > 1 ? " up to " + limitMessages + " messages" : "1 message") + ".";

    let jsonPath = matsbm_jsonpath();
    let requestBody = {
        action: action === "reissue" ? "reissue_all" : "delete_all",
        queueId: queueId,
        limitMessages: limitMessages
    };
    fetch(jsonPath, {
        method: operation, headers: {
            'Content-Type': 'application/json'
        }, body: JSON.stringify(requestBody)
    }).then(response => {
        if (!response.ok) {
            matsbm_fetch_response_not_ok_message(response);
            return;
        }
        response.json().then(result => {
            console.log(result);
            let actionMessage = document.getElementById('matsbm_action_message');
            actionMessage.innerHTML = "Done, " + result.numberOfAffectedMessages
                + " message" + (result.numberOfAffectedMessages > 1 ? "s" : "")
                + " " + actionPast
                + ". Time taken: " + result.timeTakenMillis + " ms."
                + (action === 'reissue' ? " <b>[Check console or log for new message ids!]</b>" : "");
            actionMessage.classList.add(action === "reissue" ? 'matsbm_action_reissued' : 'matsbm_action_deleted')
            for (const msgSysMsgId of Object.keys(result.affectedMessages)) {
                const row = document.getElementById('matsbm_msgid_' + msgSysMsgId);
                if (row) {
                    row.classList.add('matsbm_' + actionPast);
                } else {
                    console.error("Couldn't find message row for msgSysMsgId [" + msgSysMsgId + "].");
                }
            }

            console.log("Done, " + actionPast + " [" + result.numberOfAffectedMessages + "] messages(s):");
            let count = 0;
            for (const messageMeta of Object.values(result.affectedMessages)) {
                console.log("  " + count + ":" + JSON.stringify(messageMeta));
                count++;
            }
            // Annoying CSS "delayed transition" also somehow "overwrites" the row color transition..?!
            // Using JS hack instead, to delay second part of transition.
            setTimeout(() => {
                for (const [msgSysMsgId, messageMeta] of Object.entries(result.affectedMessages)) {
                    const row = document.getElementById('matsbm_msgid_' + msgSysMsgId);
                    if (row) {
                        row.classList.add('matsbs_delete_or_reissue');
                    }
                }
                setTimeout(() => window.location.reload(), action === "reissue" ? 2000 : 1200);
            }, 1500)
        }).catch(error => {
            matsbm_json_parse_error_message(error);
        });
    }).catch(error => {
        matsbm_fetch_error_message(error);
    });
}

// ::: EXAMINE MESSAGE

// .. Key Listener for Examine Message

function matsbm_examineMessageKeyListener(event) {
    const name = event.key;
    if (name === "Escape") {
        // ?: Is the "Confirm Delete" button active (non-hidden)?
        if (matsbm_is_delete_single_confirm_active()) {
            // -> Yes it is active - then it is this we'll escape
            matsbm_delete_single_cancel();
        } else {
            setTimeout(() => {
                document.getElementById("matsbm_back_browse_queue").click();
            }, 10);
        }
        return;
    }


    if (name.toLowerCase() === "r") {
        matsbm_click("matsbm_reissue_single");
    }
    if (name.toLowerCase() === "d") {
        matsbm_click("matsbm_delete_single");
    }
    if ((name.toLowerCase() === "x") && matsbm_is_delete_single_confirm_active()) {
        matsbm_click("matsbm_delete_single_confirm");
    }
}

// .. REISSUE / DELETE

function matsbm_reissue_single(event, queueId, msgSysMsgId) {
    matsbm_examineview_hide_all_buttons();
    // Invoke after a small delay, so that animation of hiding buttons is well underway.
    setTimeout(() => {
        matsbm_reissue_or_delete_single(event, queueId, msgSysMsgId, "reissue")
    }, 500);
}

function matsbm_delete_single_propose(event) {
    // Hide Reissue and Delete
    matsbm_hide("matsbm_reissue_single");
    matsbm_hide("matsbm_delete_single");
    // Show Delete Confirm and Delete Cancel
    matsbm_show("matsbm_delete_single_confirm");
    matsbm_show("matsbm_delete_single_cancel");
}

function matsbm_delete_single_cancel(event) {
    // Show Reissue and Delete
    matsbm_show("matsbm_reissue_single");
    matsbm_show("matsbm_delete_single");
    // Hide Delete Confirm and Delete Cancel visible
    matsbm_hide("matsbm_delete_single_confirm");
    matsbm_hide("matsbm_delete_single_cancel");
}

function matsbm_delete_single_confirm(event, queueId, msgSysMsgId) {
    matsbm_examineview_hide_all_buttons();
    // Invoke after a small delay, so that animation of hiding buttons is well underway.
    setTimeout(() => {
        matsbm_reissue_or_delete_single(event, queueId, msgSysMsgId, "delete")
    }, 500);
}

function matsbm_is_delete_single_confirm_active() {
    // Return whether the "Confirm Delete" button is non-hidden
    const confirmDeleteButton = document.getElementById("matsbm_delete_single_confirm");
    if (!confirmDeleteButton) {
        return false;
    }
    return !confirmDeleteButton.classList.contains('matsbm_button_hidden');
}

function matsbm_examineview_hide_all_buttons() {
    matsbm_hide("matsbm_delete_single_confirm");
    matsbm_hide("matsbm_delete_single_cancel");
    matsbm_hide("matsbm_reissue_single");
    matsbm_hide("matsbm_delete_single");
}

function matsbm_reissue_or_delete_single(event, queueId, msgSysMsgId, action) {
    // Hide all buttons.
    matsbm_examineview_hide_all_buttons();

    const actionPresent = action === "reissue" ? "Reissuing" : "Deleting";
    const actionPast = action === "reissue" ? "reissued" : "deleted";
    const operation = action === "reissue" ? "PUT" : "DELETE";

    console.log(actionPresent + " single message")

    document.getElementById('matsbm_action_message').textContent = actionPresent + " message [" + msgSysMsgId + "].";

    let jsonPath = matsbm_jsonpath();
    let requestBody = {
        action: action === "reissue" ? "reissue_selected" : "delete_selected",
        queueId: queueId,
        msgSysMsgIds: [msgSysMsgId]
    };
    fetch(jsonPath, {
        method: operation, headers: {
            'Content-Type': 'application/json'
        }, body: JSON.stringify(requestBody)
    }).then(response => {
        if (!response.ok) {
            matsbm_fetch_response_not_ok_message(response);
            return;
        }
        response.json().then(result => {
            console.log(result);
            let actionMessage = document.getElementById('matsbm_action_message');
            if (result.numberOfAffectedMessages !== 1) {
                actionMessage.textContent = "Message wasn't " + actionPast + "! Already " + actionPast + "?";
                actionMessage.classList.add('matsbm_action_error');
            } else {
                actionMessage.textContent = "Done, message " + actionPast + "!"
                    + (action === 'reissue' ? " (Check console for new message id)" : "")
                    + " Time taken: " + result.timeTakenMillis + " ms";
                actionMessage.classList.add(action === 'reissue' ? 'matsbm_action_reissued' : 'matsbm_action_deleted')
            }
            console.log("Done, " + actionPast + " [" + result.numberOfAffectedMessages + "] messages(s):");
            let count = 0;
            for (const messageMeta of Object.values(result.affectedMessages)) {
                console.log("  " + count + ":" + JSON.stringify(messageMeta));
                count++;
            }
            setTimeout(() => {
                // Make the entire message view "fade out to nothingness", either red or green.
                document.getElementById('matsbm_part_flow_and_message_props').classList.add('matsbm_part_hidden_' + action);
                if (document.getElementById('matsbm_part_state_and_message')) {
                    document.getElementById('matsbm_part_state_and_message').classList.add('matsbm_part_hidden_' + action);
                }
                if (document.getElementById('matsbm_part_stack')) {
                    document.getElementById('matsbm_part_stack').classList.add('matsbm_part_hidden_' + action);
                }
                if (document.getElementById('matsbm_part_matstrace')) {
                    document.getElementById('matsbm_part_matstrace').classList.add('matsbm_part_hidden_' + action);
                }
                if (document.getElementById('matsbm_part_msgrepr_tostring')) {
                    document.getElementById('matsbm_part_msgrepr_tostring').classList.add('matsbm_part_hidden_' + action);
                }
                setTimeout(() => window.location = window.location.pathname + "?browse&destinationId=queue:" + queueId,
                    1100);
            }, 500);
        }).catch(error => {
            matsbm_json_parse_error_message(error);
        });
    }).catch(error => {
        matsbm_fetch_error_message(error);
    });
}


// .. MATSTRACE CALL MODAL

let matsbm_activecallmodal = -1;

function matsbm_is_call_modal_active() {
    // Return whether the active call modal is not -1.
    return matsbm_activecallmodal !== -1;
}

function matsbm_clearcallmodal(event) {
    // Clear the "underlay" for the modal
    let modalunderlay = document.getElementById("matsbm_callmodalunderlay");
    // Don't clear if the target is the modal, to enable interaction with it.
    if (event.target !== modalunderlay) {
        return;
    }

    matsbm_clearcallmodal_noncond()
    return true;
}

function matsbm_clearcallmodal_noncond() {
    // Clear the modal underlay
    document.getElementById("matsbm_callmodalunderlay").classList.remove("matsbm_callmodalunderlay_visible")

    // Clear all call modals
    for (const modal of document.getElementsByClassName("matsbm_box_call_and_state_modal")) {
        modal.classList.remove("matsbm_box_call_and_state_modal_visible");
    }
    // Clear all call rows
    for (const row of document.body.querySelectorAll("#matsbm_table_matstrace tr")) {
        row.classList.remove("matsbm_row_active");
    }

    matsbm_activecallmodal = -1;
}

function matsbm_callmodal(event) {
    // Clear the "Confirm Delete" if active
    matsbm_delete_single_cancel();

    // Un-hide on the specific call modal
    let tr = event.target.closest("tr");
    let callno = tr.getAttribute("data-callno");
    let callmodal = document.getElementById("matsbm_callmodal_" + callno);
    console.log(callmodal);
    callmodal.classList.add("matsbm_box_call_and_state_modal_visible");

    // Un-hide the "underlay"
    let modalunderlay = document.getElementById("matsbm_callmodalunderlay");
    modalunderlay.classList.add("matsbm_callmodalunderlay_visible")

    // Make Call row active
    let callRow = document.getElementById("matsbm_callrow_" + callno);
    let processRow = document.getElementById("matsbm_processrow_" + callno);
    callRow.classList.add("matsbm_row_active")
    processRow.classList.add("matsbm_row_active")

    // Clear hover
    matsbm_hover_call_out();

    // Set the active call number
    matsbm_activecallmodal = callno;
}

// Modal Key listener: when modal is active.
function matsbm_modalActiveKeyListener(event) {
    const name = event.key;
    const code = event.code;
    console.log(`Key pressed ${name} \r\n Key code value: ${code}`);

    // ?: Was the Escape?
    if (name === "Escape") {
        // -> Yes, and call modal is active - cancel it.
        matsbm_clearcallmodal_noncond();
        return;
    }

    const currentCallModal = document.getElementById("matsbm_callmodal_" + matsbm_activecallmodal);
    const currentCallRow = document.getElementById("matsbm_callrow_" + matsbm_activecallmodal);
    const currentProcessRow = document.getElementById("matsbm_processrow_" + matsbm_activecallmodal);
    if (name === "ArrowUp") {
        matsbm_activecallmodal--;
        const nextCallModal = document.getElementById("matsbm_callmodal_" + matsbm_activecallmodal);
        if (nextCallModal) {
            // Call modal
            currentCallModal.classList.remove("matsbm_box_call_and_state_modal_visible");
            nextCallModal.classList.add("matsbm_box_call_and_state_modal_visible");
            // Call row
            currentCallRow.classList.remove("matsbm_row_active")
            currentProcessRow.classList.remove("matsbm_row_active")
            const nextCallRow = document.getElementById("matsbm_callrow_" + matsbm_activecallmodal);
            const nextProcessRow = document.getElementById("matsbm_processrow_" + matsbm_activecallmodal);
            nextCallRow.classList.add("matsbm_row_active")
            nextProcessRow.classList.add("matsbm_row_active")
            // Due to the CSS scroll-margin, this works even with sticky headers.
            document.getElementById("matsbm_callrow_" + (matsbm_activecallmodal - 1))
                .scrollIntoView({behavior: "smooth", block: "nearest"});
        } else {
            // Back out
            matsbm_activecallmodal++;
        }
        event.preventDefault();
    }
    if (name === "ArrowDown") {
        matsbm_activecallmodal++;
        const nextCallModal = document.getElementById("matsbm_callmodal_" + matsbm_activecallmodal);
        if (nextCallModal) {
            // Call modal
            currentCallModal.classList.remove("matsbm_box_call_and_state_modal_visible");
            nextCallModal.classList.add("matsbm_box_call_and_state_modal_visible");
            // Call row
            currentCallRow.classList.remove("matsbm_row_active")
            currentProcessRow.classList.remove("matsbm_row_active")
            const nextCallRow = document.getElementById("matsbm_callrow_" + matsbm_activecallmodal);
            const nextProcessRow = document.getElementById("matsbm_processrow_" + matsbm_activecallmodal);
            nextCallRow.classList.add("matsbm_row_active")
            nextProcessRow.classList.add("matsbm_row_active")
            nextCallRow.scrollIntoView({behavior: "smooth", block: "nearest"});
        } else {
            // Back out
            matsbm_activecallmodal--;
        }
        event.preventDefault();
    }
}


// .. MATSTRACE HOVER

function matsbm_hover_call(event) {
    let tr = event.target.closest("tr");
    let callno = tr.getAttribute("data-callno");
    let callRow = document.getElementById("matsbm_callrow_" + callno);
    let processRow = document.getElementById("matsbm_processrow_" + callno);
    callRow.classList.add("matsbm_row_hover")
    processRow.classList.add("matsbm_row_hover")
}

function matsbm_hover_call_out() {
    for (const row of document.body.querySelectorAll("#matsbm_table_matstrace tr")) {
        row.classList.remove("matsbm_row_hover");
    }
}

// ::: CODE TO BE RUN AFTER DOMContentLoaded

// :: Adding event listeners for "limit messages" text field, shall only allow numbers.
document.addEventListener('DOMContentLoaded', () => {
    const input = document.getElementById('matsbm_all_limit_messages');
    if (input) {
        input.addEventListener('input', function () {
            this.value = this.value.replace(/[^\d]/g, '');
        });
    }
});