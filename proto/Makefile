all: none


tests_pb2.py: tests.proto
	protoc -I=. --python_out=. tests.proto

clean:
	rm -f *pyc

realclean: clean
	rm -f tests_pb2.py
