#!/usr/bin/env python3

"""
Converts a ffmpeg vmaf json file to a csv file

"""

import argparse
import json
import os
import pandas
import sys


default_values = {
    "debug": 0,
    "dry_run": False,
    "infile": None,
    "outfile": None,
}


def process_infile(infile, outfile, debug):
    assert os.path.isfile(infile), f"error: cannot open {infile}"
    # 1. read the input json file
    with open(infile, "r") as fin:
        json_text = fin.read()
    json_dict = json.loads(json_text)
    vmaf_info = pandas.DataFrame(
        {"frame_num": list(d["frameNum"] for d in json_dict["frames"])}
    )
    for key in json_dict["frames"][0]["metrics"].keys():
        vmaf_info[key] = list(d["metrics"][key] for d in json_dict["frames"])

    # 2. write the results into a csv file
    with open(outfile, "w") as fout:
        # write the header
        keys = list(vmaf_info.keys())
        fout.write(",".join(keys) + "\n")
        for _, row in vmaf_info.iterrows():
            vals = list(row)
            # ensure first value is int
            vals[0] = int(vals[0])
            fout.write(",".join(str(v) for v in vals) + "\n")


def get_options(argv):
    """Generic option parser.

    Args:
        argv: list containing arguments

    Returns:
        Namespace - An argparse.ArgumentParser-generated option object
    """
    # init parser
    # usage = 'usage: %prog [options] arg1 arg2'
    # parser = argparse.OptionParser(usage=usage)
    # parser.print_help() to get argparse.usage (large help)
    # parser.print_usage() to get argparse.usage (just usage line)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "-v",
        "--version",
        action="store_true",
        dest="version",
        default=False,
        help="Print version",
    )
    parser.add_argument(
        "-d",
        "--debug",
        action="count",
        dest="debug",
        default=default_values["debug"],
        help="Increase verbosity (use multiple times for more)",
    )
    parser.add_argument(
        "--quiet",
        action="store_const",
        dest="debug",
        const=-1,
        help="Zero verbosity",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        dest="dry_run",
        default=default_values["dry_run"],
        help="Dry run",
    )
    parser.add_argument(
        "-i",
        "--infile",
        dest="infile",
        type=str,
        default=default_values["infile"],
        metavar="input-file",
        help="input file",
    )
    parser.add_argument(
        "-o",
        "--outfile",
        dest="outfile",
        type=str,
        default=default_values["outfile"],
        metavar="output-file",
        help="output file",
    )
    # do the parsing
    options = parser.parse_args(argv[1:])
    if options.version:
        return options
    # force analysis coherence
    return options


def main(argv):
    # parse options
    options = get_options(argv)
    # get infile/outfile
    if options.infile == "-" or options.infile is None:
        options.outfile = "/dev/fd/0"
    if options.outfile == "-" or options.outfile is None:
        options.outfile = "/dev/fd/1"
    # print results
    if options.debug > 0:
        print(options)

    if options.infile is not None:
        process_infile(options.infile, options.outfile, options.debug)
    else:
        print("Input file is missing.")


if __name__ == "__main__":
    # at least the CLI program name: (CLI) execution
    main(sys.argv)
