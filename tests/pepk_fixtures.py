"""Explicit policy states only; never write the approved production record."""
from copy import deepcopy


def disabled_record(record):
    fixture=deepcopy(record)
    fixture.update(status='disabled-test-fixture',export_implementation_reviewed=False,
                   independent_production_review_id=None,private_delivery_review_id=None)
    return fixture


def approved_record(record):
    fixture=deepcopy(record)
    fixture.update(status='approved_for_one_time_export',export_implementation_reviewed=True,
                   independent_production_review_id='test-fixture/production',
                   private_delivery_review_id='test-fixture/private-delivery')
    return fixture
