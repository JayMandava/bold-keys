"""Pure, secret-independent policy gates shared by PEPK preparation/export."""
import re

CERT = 'b237e1aec0bfdae579caebe1a0d97517af1b7c066adc4b3d197b641adfa46c34'
SOURCE_SHA = 'dc29a1d192eef9371eec0d2c134879fefc0a9dcf0c7046d6c8f3d302d0009b09'
TOOL_SHA = '7923237dd6ea1c0198b0ee91a1bfc0dafbd858aa88936f2ce847652e93d1e18a'
BUILD_SHA = 'bbbd1e62b16bf37f87242e00da5b9b2c7f5e00779f98590c09cc81bc6a5b0a48'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def validate_ready(record):
    # All checks precede any signing-environment lookup, decoding or private-file creation.
    require(record.get('schema') == 3, 'Unsupported export review record')
    require(record.get('expected_certificate_sha256') == CERT, 'Certificate pin mismatch')
    require(record.get('official_source_sha256') == SOURCE_SHA, 'Source pin mismatch')
    require(record.get('source_built_tool_sha256') == TOOL_SHA, 'Source-built tool pin mismatch')
    require(record.get('build_script_sha256') == BUILD_SHA, 'Build script pin mismatch')
    pem = record.get('console_encryption_pem_sha256')
    require(isinstance(pem, str) and re.fullmatch('[0-9a-f]{64}', pem), 'Missing authenticated Console encryption PEM pin')
    notices = record.get('third_party_notices_sha256')
    require(isinstance(notices, str) and re.fullmatch('[0-9a-f]{64}', notices), 'Missing reviewed third-party notice payload pin')
    for name in ('build_recipe_review_id', 'source_licence_review_id', 'controlled_pty_protocol_review_id',
                 'private_delivery_review_id', 'independent_production_review_id'):
        require(isinstance(record.get(name), str) and bool(record[name].strip()), 'Missing independent production/licence/PTY/private-delivery review')
    require(record.get('password_input_source_verified') is True, 'Password source review missing')
    require(record.get('encryption_and_output_source_verified') is True, 'Encryption/output source review missing')
    require(record.get('export_implementation_reviewed') is True, 'Independent production implementation review missing')
    require(record.get('private_delivery_repository') == 'JayMandava/bold-keys-signing-delivery', 'Fixed private destination is mandatory')
    require(record.get('status') == 'approved_for_one_time_export', 'One-time export remains disabled')


def validate_execution_context(commit, env):
    expected = {'GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_REPOSITORY':'JayMandava/bold-keys',
                'GITHUB_REF':'refs/heads/main','GITHUB_SHA':commit,'GITHUB_WORKFLOW_SHA':commit,
                'PRIVATE_DELIVERY_CHECKED':'true'}
    for name, value in expected.items():
        require(env.get(name)==value, 'Reviewed exact-main/private-delivery execution context missing')
