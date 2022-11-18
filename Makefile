

all: none


test:
	./scripts/tests/unit/adb_cmds_test.py
	./scripts/tests/unit/app_utils_test.py
	./scripts/tests/unit/encapp_test.py 


/tmp/akiyo_qcif.y4m:
	./scripts/prepare_test_data.sh


verify: /tmp/akiyo_qcif.y4m
	./scripts/encapp_verify.py -i /tmp/akiyo_qcif.y4m -ddd
