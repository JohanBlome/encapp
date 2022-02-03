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
    ltr-2ref.json \
    ltr-4ref.json \
    simple.json \
    tl2.json
	echo "TMPFILE: ${TMPFILE}"
	@for file in $^ ; do \
		echo "processing $$file" ; \
		rm -f ${TMPFILE}; \
		jq --indent 4 . $$file > ${TMPFILE}; \
		if [[ $$? -eq "0" ]]; then \
			mv ${TMPFILE} $$file; \
		fi \
	done
