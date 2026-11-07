"""Trace catalog assertions to tests actually executed in JUnit. Never infer semantic E2E pass."""

import json
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / ".tools/scenario-run-20261107"
MAPPING = {}


def add(area, names):
    for n, name in enumerate(names, 1):
        if name:
            MAPPING[f"DG-{area}-{n:03}"] = name.split("|")


add(
    "AUTO",
    [
        "test_normal_answer_posts_once_without_opening_a_dm",
        "test_catalog_auto_customer_answer",
        "test_catalog_auto_customer_answer",
        "test_unavailable_required_reads_route_to_private_developer_not_public_failure",
        "test_only_exception_opens_dm_and_result_posts_back_to_original_thread",
        "test_internal_failures_have_one_private_action_and_never_post_or_rerun",
        "test_catalog_auto_customer_answer",
        "test_catalog_auto_customer_answer",
        "test_developer_result_resumes_same_inquiry_and_requires_final_approval",
        "test_catalog_auto_customer_answer",
        "test_history_change_does_not_publish_an_outdated_answer_or_loop",
        "test_parallel_auto_delivery_claim_posts_once",
        "test_old_review_draft_does_not_auto_post_after_upgrade",
        "test_review_mode_cannot_be_bypassed_by_automatic_entrypoint",
        "test_normal_answer_posts_once_without_opening_a_dm",
        "test_normal_answer_posts_once_without_opening_a_dm",
        "test_only_exception_opens_dm_and_result_posts_back_to_original_thread",
        "test_insufficient_developer_report_waits_again_not_an_infinite_loop",
    ],
)
add(
    "IMAGE",
    [
        "test_uploaded_image_reaches_engine_and_requires_approval",
        "test_catalog_image_boundaries",
        "test_followup_resume_gets_prior_image_again_and_no_other_channel_images",
        "test_changed_image_invalidates_approval",
        "test_image_failure_creates_actionable_dm_without_model_or_public_answer",
        "test_more_than_four_images_fails_before_downloading",
        "test_catalog_image_boundaries",
        "test_invalid_or_oversize_bytes_fail",
        "test_total_size_limit_is_not_silent_truncation",
        "test_catalog_image_boundaries",
        "test_unsupported_types_fail",
        "test_unsupported_types_fail",
        "test_invalid_blocks_are_a_clear_attachment_error",
        "test_html_login_page_and_size_mismatch_not_sent_to_model",
        None,
        None,
        "test_retry_cannot_downgrade_code_requirement_to_visual_only",
        "test_download_read_is_bounded|test_image_failure_creates_actionable_dm_without_model_or_public_answer",
    ],
)
add(
    "SESSION",
    [
        "test_followup_resumes_and_includes_published_manual_edit",
        "test_new_top_level_message_never_inherits_channel_session",
        "test_followup_resume_gets_prior_image_again_and_no_other_channel_images",
        "test_followup_receives_new_question_and_posted_manual_context",
        "test_catalog_rejected_draft_not_public_history",
        "test_session_not_found_gets_only_one_fresh_retry",
        "test_repeated_missing_session_does_not_loop",
        "test_resume_does_not_reuse_prior_call_read_receipts",
        "test_catalog_thread_lock",
        "test_developer_report_is_never_shared_with_another_inquiry",
        None,
        None,
        "test_changed_image_invalidates_approval",
        "test_thread_change_requires_regeneration_and_new_approval",
        "test_thread_pagination_no_silent_truncation|test_pagination_loop_is_failure",
        None,
    ],
)
add(
    "HANDOFF",
    [
        "test_only_exception_opens_dm_and_result_posts_back_to_original_thread",
        "test_unavailable_required_reads_route_to_private_developer_not_public_failure",
        "test_developer_result_resumes_same_inquiry_and_requires_final_approval",
        "test_lookup_result_returns_new_draft_with_timestamp_and_requires_approval",
        "test_lookup_never_accepts_unsafe_or_unbounded_sql",
        "test_lookup_never_accepts_unsafe_or_unbounded_sql",
        "test_lookup_never_accepts_unsafe_or_unbounded_sql",
        "test_developer_result_resumes_same_inquiry_and_requires_final_approval",
        "test_developer_result_resumes_same_inquiry_and_requires_final_approval",
        "test_developer_result_resumes_same_inquiry_and_requires_final_approval",
        "test_environment_repair_queues_once_and_requires_actual_reads|test_repair_report_is_not_code_or_live_evidence",
        "test_insufficient_developer_report_waits_again_not_an_infinite_loop",
        None,
        "test_lookup_time_requires_explicit_zone_and_not_future",
        "test_invalid_or_sensitive_results_do_not_change_state",
        "test_invalid_or_sensitive_results_do_not_change_state",
        "test_card_order_labels_and_public_payload_are_distinct",
        "test_assistance_rejects_wrong_identity_or_stale_binding",
        "test_actual_bolt_assistance_button_and_modal_queue_durably_before_ack",
        "test_assistance_rejects_wrong_identity_or_stale_binding",
        "test_old_failure_card_cannot_resubmit_after_a_new_failure",
        "test_double_modal_submissions_create_one_job",
        "test_assistance_rejects_wrong_identity_or_stale_binding",
        "test_catalog_unregistered_direct_messages",
        "test_catalog_unregistered_direct_messages",
        "test_failed_submission_transaction_rolls_back",
        "test_internal_failure_fix_requires_research_then_posts_without_confirmation",
        "test_internal_failures_have_one_private_action_and_never_post_or_rerun",
        "test_uncertain_first_progress_dm_is_not_sent_twice",
        "test_internal_failures_have_one_private_action_and_never_post_or_rerun",
    ],
)
add(
    "DELIVERY",
    [
        "test_normal_answer_posts_once_without_opening_a_dm",
        "test_large_code_reply_preserves_fences_without_footer",
        "test_catalog_long_line_delivery",
        "test_delivery_failure_is_private_and_never_automatically_repeated",
        "test_unknown_delivery_never_blindly_resends",
        "test_partial_multimessage_failure_cannot_repost_first_part",
        "test_unknown_delivery_never_blindly_resends",
        "test_durable_ready_answer_survives_crash_without_second_model_call",
        "test_crash_after_auto_claim_without_send_requires_developer",
        "test_stop_before_post_preserves_ready_answer_without_sending",
        "test_stop_blocks_post_and_does_not_delete_state",
        "test_definite_failure_retry_preserves_external_send_identity",
        "test_unknown_delivery_never_blindly_resends",
        "test_unknown_delivery_never_blindly_resends",
        "test_other_bot_cannot_supply_positive_delivery_evidence",
        "test_old_database_snapshot_cannot_hide_a_later_post",
        "test_absent_dougle_emoji_falls_back_and_restored_identity_keeps_loading",
        "test_identity_stays_while_only_state_changes",
        "test_late_engine_result_cannot_resurrect_cancelled_inquiry_or_session",
        "test_queued_cancel_is_durable_and_does_not_stop_other_inquiry",
    ],
)
add(
    "OPS",
    [
        "test_keychain_read",
        "test_keychain_read",
        "test_keychain_read",
        "test_keychain_wrong_prefix_is_deleted_without_leak",
        "test_keychain_stores_without_password_argument_or_capture",
        "test_config_round_trip_permissions_and_no_secret_fields",
        "test_invalid_channel_links",
        "test_cli_first_setup_status_menu_and_channel_add",
        "test_cli_first_setup_status_menu_and_channel_add",
        "test_process_lock_prevents_two_runtimes",
        "test_status_exposes_elapsed_and_state_without_question_or_source",
        "test_status_checks_daemon_version_not_cli_version",
        "test_daily_limit_blocks_style_retry_without_extra_call",
        "test_budget_reservations_are_not_zero_on_unknown_cost",
        "test_daily_limit_blocks_style_retry_without_extra_call",
        "test_retries_share_one_deadline",
        "test_missing_plain_language_never_becomes_approvable",
        "test_owned_cli_is_terminated_on_cancel_while_bot_remains_enabled",
        "test_subprocess_timeout_is_bounded_and_does_not_leak_stderr",
        "test_hook_blocks_unapproved_tools_and_sensitive_paths",
        "test_hook_allows_only_memory_writes_and_rejects_symlink_escape",
        "test_hook_blocks_unapproved_tools_and_sensitive_paths",
        "test_memory_root_symlink_cannot_authorize_business_writes",
        "test_nonmac_keychain_never_calls_security",
    ],
)


def main():
    root = ET.parse(RUN / "runtime-final.xml").getroot()
    tests = list(root.iter("testcase"))
    rows = []
    for ident, names in MAPPING.items():
        matched = [t for t in tests if t.attrib["name"].split("[")[0] in names]
        missing = [
            n
            for n in names
            if not any(t.attrib["name"].split("[")[0] == n for t in matched)
        ]
        passed = not missing and all(len(t) == 0 for t in matched)
        rows.append(
            {
                "id": ident,
                "layer": "production_runtime_fake_services",
                "result": "PASS" if passed else "FAIL",
                "tests": [
                    t.attrib["classname"] + "::" + t.attrib["name"] for t in matched
                ],
                "missing_tests": missing,
                "case_complete": False,
                "remaining": "Only the mapped runtime assertions are proven; semantic answer quality, real Slack/Claude and multi-step acceptance require their own evidence.",
            }
        )
    (RUN / "runtime-mapping-results.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=2) + "\n"
    )
    print(len(rows), sum(r["result"] == "FAIL" for r in rows))


if __name__ == "__main__":
    main()
