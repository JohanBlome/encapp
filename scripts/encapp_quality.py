#!/usr/bin/env python3

"""Python script to run ENCAPP tests on Android and collect results.
The script will create a directory based on device model and date,
and save encoded video and rate distortion results in the directory
"""

import os
import json
import sys
import argparse
from argparse import RawTextHelpFormatter
import re

from os.path import exists
from encapp import run_cmd
from encapp import convert_to_bps

VMAF_RE = '<metric name=\"vmaf\" min=\"(?P<min>[0-9.]*)\" ' \
          'max=\"(?P<max>[0-9.]*)\" mean=\"(?P<mean>[0-9.]*)\" ' \
          'harmonic_mean=\"(?P<hmean>[0-9.]*)\" />'
PSNR_RE = 'average:([0-9.]*)'
SSIM_RE = 'SSIM Y:([0-9.]*)'
FFMPEG_SILENT = 'ffmpeg  -hide_banner -loglevel error -y '

extra_settings = {
    'media_path': None,
    'override_reference': None,
    'pix_fmt': None,
    'reference_resolution': None,
    'fr_fr': False,
    'fr_lr': False,
    'lr_lr': False,
    'lr_fr': False,
    'recalc': False,
}


def parse_quality(vmaf_file, ssim_file, psnr_file):
    """Read calculated log/output files and pick relevant vmaf/ssim/psnr
       data
    """
    # currently only vmaf
    vmaf = -1
    with open(vmaf_file) as input_file:
        line = " "
        while len(line) > 0:
            line = input_file.readline()
            match = re.search(VMAF_RE, line)
            if match:
                vmaf = int(round(float(match.group('mean'))))
                break
    ssim = -1
    with open(ssim_file) as input_file:
        line = " "
        while len(line) > 0:
            line = input_file.readline()
            match = re.search(SSIM_RE, line)
            if match:
                ssim = round(float(match.group(1)), 2)
                break

    psnr = -1
    with open(psnr_file) as input_file:
        line = " "
        while len(line) > 0:
            line = input_file.readline()
            match = re.search(PSNR_RE, line)
            if match:
                psnr = round(float(match.group(1)), 2)
                break

    return vmaf, ssim, psnr

def get_media_props(mediapath):
    status, std_out, std_err = run_cmd(f'ffprobe {mediapath}')
    resolution = None
    if status:
        print(f'std_out = {std_out}, err = {std_err}')

    m = re.search('([0-9]{2,4})x([0-9]{2,4})', std_err)
    if m:
        resolution = m.group(0)
    return resolution

def run_quality(test_file, optionals):
    """Compare the output found in test_file with the source/reference
       found in options.media directory or overriden
    """
    with open(test_file, 'r') as input_file:
        test = json.load(input_file)

    source = optionals['media_path'] + "/" + test.get('sourcefile')
    if len(optionals['override_reference']) > 0:
        source = optionals['override_reference']

    # For raw we assume the source is the same resolution as the media
    # For surface transcoding look at decoder_media_format"

    # Assume encoded file in same directory as test result json file
    directory, _ = os.path.split(test_file)
    encodedfile = directory + '/' + test.get('encodedfile')

    vmaf_file = f'{encodedfile}.vmaf'
    ssim_file = f'{encodedfile}.ssim'
    psnr_file = f'{encodedfile}.psnr'

    settings = test.get('settings')
    fps = settings.get('fps')
    if (exists(vmaf_file) and exists(ssim_file) and exists(psnr_file) and
            not optionals['recalc']):
        print('All quality indicators already calculated for media, '
              f'{vmaf_file}')
    else:
        input_media_format = test.get('decoder_media_format')
        raw = True
        pix_fmt = optionals['pix_fmt']

        if isinstance(input_media_format, str):
            # surface mode
            raw = False
        else:
            input_media_format = test.get('encoder_media_format')
        if len(pix_fmt) == 0:
            # See if source contains a clue
            pix_fmt = 'yuv420p'
            if source.find('nv12') > -1:
                pix_fmt = 'nv12'

        output_media_format = test.get('encoder_media_format')
        output_width = output_media_format.get('width')
        output_height = output_media_format.get('height')

        output_res = f'{output_width}x{output_height}'
        media_res = get_media_props(encodedfile)
        if output_res != media_res:
            print(f'Warning. Discrepancy in resolutions for output')
            print(f'Json {output_res}, media {media_res}')
            output_res = media_res

        if len(optionals['reference_resolution']) > 0:
            input_res = optionals['reference_resolution']
        else:

            input_width = input_media_format.get('width')
            input_height = input_media_format.get('height')
            #If we did not get aything here use the encoded size
            if input_width == None or input_height is None or \
                (not input_width.isnumeric() and not input_height.isnumeric()):
                print(f'Warning. Input size if wrong.')
                print(f'Json {input_width}x{input_height}')
                input_res = output_res
            else:
                input_res = f'{input_width}x{input_height}'


        reference = source
        if not os.path.exists(reference):
            print(f'Reference {reference} is unavailable')
            exit(-1)
        distorted = encodedfile
        if not os.path.exists(source):
            print(f'Source {source} is unavailable')
            exit(-1)

        shell_cmd = ''

        force_scale = ''
        if optionals['fr_fr']:
            force_scale = 'scale=in_range=full:out_range=full[o];[o]'
        if optionals['fr_lr']:
            force_scale = 'scale=in_range=full:out_range=limited[o];[o]'
        if optionals['lr_lr']:
            force_scale = 'scale=in_range=limited:out_range=limited[o];[o]'
        if optionals['lr_fr']:
            force_scale = 'scale=in_range=limited:out_range=full[o];[o]'

        if input_res != output_res:
            distorted = f'{encodedfile}.yuv'

            # Scale
            shell_cmd = (f'{FFMPEG_SILENT} -i {encodedfile} -f rawvideo '
                       f'-pix_fmt {pix_fmt} -s {input_res} {distorted}')

            run_cmd(shell_cmd)
        if raw:
            ref_part = (f'-f rawvideo -pix_fmt {pix_fmt} -s {input_res} '
                        f'-r {fps} -i {reference} ')
        else:
            ref_part = f'-r {fps} -i {reference} '

        print(f'input res = {input_res} vs {output_res}')
        if input_res != output_res:
            dist_part = (f'-f rawvideo -pix_fmt {pix_fmt} '
                         f'-s {input_res} -r {fps} -i {distorted}')
        else:
            dist_part = f'-r {fps} -i {distorted} '

        # Do calculations
        if optionals['recalc'] or not exists(vmaf_file):
            shell_cmd = (f'{FFMPEG_SILENT} {ref_part} {dist_part} '
                       '-filter_complex '
                       f'"{force_scale}libvmaf=log_path={vmaf_file}:n_threads=16" '
                       '-report -f null - 2>&1 ')
            run_cmd(shell_cmd)
        else:
            print(f'vmaf already calculated for media, {vmaf_file}')

        if optionals['recalc'] or not exists(ssim_file):
            shell_cmd = (f'ffmpeg {dist_part} {ref_part} '
                       '-filter_complex '
                       f'"{force_scale}ssim=stats_file={ssim_file}.all" '
                       f'-f null - 2>&1 | grep SSIM > {ssim_file}')
            run_cmd(shell_cmd)
        else:
            print(f'ssim already calculated for media, {ssim_file}')

        if optionals['recalc'] or not exists(psnr_file):
            shell_cmd = (f'ffmpeg {dist_part} {ref_part} '
                       '-filter_complex '
                       f'"{force_scale}psnr=stats_file={psnr_file}.all" '
                       f'-f null - 2>&1 | grep PSNR > {psnr_file}')
            run_cmd(shell_cmd)
        else:
            print(f'psnr already calculated for media, {psnr_file}')

        if distorted != encodedfile:
            os.remove(distorted)

    if exists(vmaf_file):
        vmaf, ssim, psnr = parse_quality(vmaf_file, ssim_file, psnr_file)

        # media,codec,gop,fps,width,height,bitrate,real_bitrate,size,vmaf,
        # ssim,psnr,file
        file_size = os.stat(encodedfile).st_size
        data = (f"{encodedfile}, {settings.get('codec')}, "
                f"{settings.get('gop')}, "
                f"{settings.get('fps')}, {settings.get('width')}, "
                f"{settings.get('height')}, "
                f"{convert_to_bps(settings.get('bitrate'))}, "
                f"{settings.get('meanbitrate')}, "
                f"{file_size}, {vmaf}, {ssim}, {psnr}, {test_file}\n")
        return data
    return None


def get_options(argv):
    """ Parse cli args """
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=RawTextHelpFormatter)

    parser.add_argument('test', nargs='*',
                        help='Test result in JSON format.')
    parser.add_argument('-o', '--output',
                        help='csv output',
                        default='quality.csv')
    parser.add_argument('--media',
                        help='Media directory',
                        default='')
    parser.add_argument('--pix_fmt',
                        help='pixel format i.e. nv12 or yuv420p',
                        default='')
    parser.add_argument('-ref', '--override_reference',
                        help=('Override reference, used when source is '
                              ' downsampled prior to encoding'),
                        default='')
    parser.add_argument('-ref_res', '--reference_resolution',
                        help='Overriden reference resolution WxH',
                        default='')
    parser.add_argument('--header',
                        help='print header to output',
                        action='store_true')
    parser.add_argument('--fr_fr',
                        help=('force full range to full range on '
                              'distorted file'),
                        action='store_true')
    parser.add_argument('--lr_fr',
                        help='force lr range to full range on distorted file',
                        action='store_true')
    parser.add_argument('--lr_lr',
                        help=('force limited range to limited range on '
                              'distorted file'),
                        action='store_true')
    parser.add_argument('--fr_lr',
                        help=('force full range to limited range on '
                              'distorted file'),
                        action='store_true')
    parser.add_argument('--recalc',
                        help='recalculate regardless of status',
                        action='store_true')

    options = parser.parse_args()

    if len(argv) == 1:
        parser.print_help()
        sys.exit()

    return options


def main(argv):
    """Calculate video quality properties (vmaf/ssim/psnr) and write
       a csv with relevant data
    """
    options = get_options(argv)

    with open(options.output, 'a') as output:
        if options.header:
            output.write('media,codec,gop,fps,width,height,'
                         'bitrate,real_bitrate,size,vmaf,ssim,'
                         'psnr,file\n')
        settings = extra_settings
        settings['media_path'] = options.media
        settings['override_reference'] = options.override_reference
        settings['pix_fmt'] = options.pix_fmt
        settings['reference_resolution'] = options.reference_resolution
        settings['fr_fr'] = options.fr_fr
        settings['fr_lr'] = options.fr_lr
        settings['lr_lr'] = options.lr_lr
        settings['lr_fr'] = options.lr_fr
        settings['recalc'] = options.recalc

        for test in options.test:
            data = run_quality(test, settings)
            if data != None:
                output.write(data)


if __name__ == '__main__':
    main(sys.argv)
