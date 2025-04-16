

all: none


test:
	./encapp/tests/unit/adb_cmds_test.py
	./encapp/tests/unit/app_utils_test.py
	./encapp/tests/unit/encapp_test.py


/tmp/akiyo_qcif.y4m:
	./encapp/prepare_test_data.sh


verify: /tmp/akiyo_qcif.y4m
	./encapp/encapp_verify.py -i /tmp/akiyo_qcif.y4m -ddd


build:
	./gradlew build

install:
	./gradlew installDebug

clean:
	./gradlew clean
