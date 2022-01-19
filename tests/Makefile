all: none


TMPFILE := $(shell mktemp /tmp/tmp.file.XXXXXXXX)

.PHONY: lint
lint: \
    bitrates.json \
    dynamic_bitrate.json \
    dynamic_framedrop.json \
    dynamic_framerate.json \
    dynamic_idr.json \
    extraconfig.json \
    idr.json \
    lt2.json \
    ltr.json \
    ltr-2ref.json \
    simple.qcif.json \
    simple.720p.json
	echo "TMPFILE: ${TMPFILE}"
	@for file in $^ ; do \
		echo "processing $$file" ; \
		rm -f ${TMPFILE}; \
		jq --indent 4 . $$file > ${TMPFILE}; \
		mv ${TMPFILE} $$file; \
	done
