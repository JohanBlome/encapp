python_path=../scripts/proto/
swift_path=../ios/Encapp/
# java will be generated when building with gradle
all: tests.proto
	protoc -I=. --python_out=$(python_path) tests.proto
	protoc -I=. --swift_out=$(swift_path) tests.proto
clean:
	rm -f *pyc

realclean: clean
	rm -f $(python_path)tests_pb2.py
	rm -f $(swift_path)tests.pb.swift
