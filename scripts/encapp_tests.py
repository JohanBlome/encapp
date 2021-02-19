#!/usr/local/bin/python3
import os
import subprocess
import json
import sys
import argparse
import re
from pathlib import Path
from datetime import datetime

KEY_NAME_DESCRIPTION = 'description'
KEY_NAME_INPUT_FILES = 'input_files'
KEY_NAME_CODECS = 'codecs'
KEY_NAME_ENCODE_RESOLUTIONS = 'encode_resolutions'
KEY_NAME_RC_MODES = 'rc_modes'
KEY_NAME_BITRATES = 'bitrates'
KEY_NAME_I_INTERVALS = 'i_intervals'
KEY_NAME_DURATION = 'duration'
KEY_NAME_USE_SURFACE_ENC = 'use_surface_enc'
KEY_NAME_INPUT_FORMAT = 'input_format'
KEY_NAME_INPUT_RESOLUTION = 'input_resolution'
KEY_NAME_I_FRAME_SIZES = 'i_frame_sizes'
KEY_NAME_TEMPORAL_LAYER_COUNTS = 'temporal_layer_counts'
KEY_NAME_ENC_LOOP = 'enc_loop'

sample_config_json_data = [
    {
        KEY_NAME_DESCRIPTION: 'sample',
        KEY_NAME_INPUT_FILES: [''],
        KEY_NAME_USE_SURFACE_ENC: 1,
        KEY_NAME_INPUT_FORMAT: 'mp4',
        KEY_NAME_INPUT_RESOLUTION: '1280x720',
        KEY_NAME_CODECS: ['hevc'],
        KEY_NAME_ENCODE_RESOLUTIONS: ['1280x720'],
        KEY_NAME_RC_MODES: ['cbr'],
        KEY_NAME_BITRATES: [500, 1000, 1500, 2000, 2500],
        KEY_NAME_I_INTERVALS: [2],
        # DEFAULT, MEDIUM, HUGE, UNLIMITED
        KEY_NAME_I_FRAME_SIZES:['unlimited'],
        # Upto 3 temporal layers
        KEY_NAME_TEMPORAL_LAYER_COUNTS: [1],
        KEY_NAME_DURATION: 10,
        KEY_NAME_ENC_LOOP: 0
    }
]
TEST_CLASS_NAME = 'com.facebook.encapp.CodecValidationInstrumentedTest'
JUNIT_RUNNER_NAME = \
    'com.facebook.encapp.test/android.support.test.runner.AndroidJUnitRunner'
ENCAPP_OUTPUT_FILE_NAME_RE = r'encapp_.*(?:\.json$)'
RD_RESULT_FILE_NAME = 'rd_results.json'
VMAF_2_0_MODEL_FILE = "/usr/local/share/model/vmaf_v0.6.1.json"

def run_cmd(cmd):
    ret = True
    try:
        print(cmd, sep=' ')
        process = subprocess.Popen(cmd, shell=True,
                                   stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE)
        stdout, stderr = process.communicate()
    except Exception:
        ret = False
        print('Failed to run command: ' + cmd)

    return ret, stdout.decode(), stderr.decode()


def check_device(serial_no):
    # check for devices
    adb_cmd = 'adb devices -l'
    ret, stdout, stderr = run_cmd(adb_cmd)
    if ret is False:
        print('Failed to get adb devices')
        sys.exit()
    device_serials = []

    for line in stdout.split('\n'):
        if line == 'List of devices attached' or line == '':
            continue
        serial = line.split()[0]
        device_serials.append(serial)

    if len(device_serials) == 0:
        print('No device connected')
        exit(0)
    elif len(device_serials) > 1:
        if (serial_no is None):
            print('More than one devices connected. \
                   Please specifiy a device serial number')
            exit(0)
        elif (serial_no not in device_serials):
            print('Specified serial number {} is invalid.'.format(serial_no))
            exit(0)
    else:
        serial_no = serial

    # get device model
    line = re.findall(serial_no + '.*$', stdout, re.MULTILINE)
    model_re = re.compile(r'model:(?P<model>\S+)')
    match = model_re.search(line[0])
    device_model = match.group('model') if match.group('model') else 'generic'

    # remove any files that are generated in previous runs
    adb_cmd = 'adb -s ' + serial_no + ' shell ls /sdcard/'
    ret, stdout, stderr = run_cmd(adb_cmd)
    output_files = re.findall(ENCAPP_OUTPUT_FILE_NAME_RE, stdout, re.MULTILINE)
    for file in output_files:
        if file == '':
            continue
        # remove the output
        adb_cmd = 'adb -s ' + serial_no + ' shell rm /sdcard/' + file
        run_cmd(adb_cmd)

    return device_model, serial_no


class VideoAnalyzer:
    def __init__(self, id):
        self.temp_dir = 'temp' + '_' + id
        self.source_file_infos = {}
        os.system('mkdir -p ' + self.temp_dir)

    def finish(self):
        os.system('rm -rf ' + self.temp_dir)

    @staticmethod
    def get_bitrate(video_file):

        if video_file is None:
            raise Exception('Video file does not exist')

        if video_file.endswith('.webm'):
            webm_info_cmd = 'webm_info -i ' + video_file +\
                    ' -clusters -clusters_size'
            ret, stdout, stderr = run_cmd(webm_info_cmd)
            if ret is False:
                raise Exception('Failed to extract video bitrate')
            duration = 0.0
            for line in stdout.split('\n'):
                if 'Duration (sec)' in line:
                    duration_str = line.split(':')[1]
                    duration += float(duration_str.strip())
                if 'Clusters (size):' in line:
                    total_bytes_str = line.split(':')[1]
                    total_bytes = int(total_bytes_str.strip())

            bitrate = 0,
            if duration != 0:
                bitrate = int(total_bytes*8/duration/1024)
            return bitrate

        if video_file.endswith('.mp4'):
            ffprobe_cmd = 'ffprobe -v error -select_streams v:0 -show_entries \
                stream=bit_rate -of default=noprint_wrappers=1:nokey=1 ' \
                    + video_file
        else:
            ffprobe_cmd = 'ffprobe -v error -show_entries format=bit_rate \
                -of default=noprint_wrappers=1:nokey=1 '\
                          + video_file
        ret, stdout, stderr = run_cmd(ffprobe_cmd)
        if stdout == '':
            raise Exception('Failed to extract video bitrate')

        bitrate = round(int(stdout.strip('\n'))/1000.0, 0)
        return bitrate

    @staticmethod
    def get_resolution(mp4_file):
        ffprobe_cmd = 'ffprobe -v error -select_streams v:0 -show_entries '\
            + 'stream=width,height -of default=noprint_wrappers=1:nokey=1 ' \
            + mp4_file
        ret, stdout, stderr = run_cmd(ffprobe_cmd)
        if stdout == '':
            raise Exception('Failed to extract video resolution')
        lines = stdout.split('\n')
        width = lines[0]
        height = lines[1]

        ffprobe_cmd = 'ffprobe -v error -select_streams v:0  -show_entries '\
            + 'stream_tags=rotate  -of default=noprint_wrappers=1:nokey=1 '\
            + mp4_file
        ret, stdout, stderr = run_cmd(ffprobe_cmd)
        if stdout != '':
            rotation = abs(int(stdout.strip('\n')))
            if rotation == 90 or rotation == 270:
                return height+'x'+width

        return width+'x'+height

    def get_source_info(self, mp4_file_ref):
        source_info = self.source_file_infos.get(mp4_file_ref)
        if source_info is None:
            input_yuv_file = self.temp_dir+'/' +\
                             mp4_file_ref.strip('.mp4')+'.yuv'
            ffmpeg_cmd = 'ffmpeg -y -i ' + mp4_file_ref + \
                         ' -pix_fmt yuv420p ' + input_yuv_file
            run_cmd(ffmpeg_cmd)
            res = self.get_resolution(mp4_file_ref)
            source_info = {'input_yuv_file': input_yuv_file,
                           'resolution': res}
            self.source_file_infos[mp4_file_ref] = source_info
        else:
            input_yuv_file = source_info.get('input_yuv_file')
            res = source_info.get('resolution')
        return input_yuv_file, res

    def get_vmaf_score(self, mp4_file_ref, mp4_file_out, duration):
        ret, stdout, stderr = run_cmd("ffmpeg -filters | grep libvmaf")
        log_file = str(Path(mp4_file_out).with_suffix(''))+'_vmaf.json'
        mp4_file_ref_res = self.get_resolution(mp4_file_ref)
        mp4_file_out_res = self.get_resolution(mp4_file_out)
        if mp4_file_ref_res == mp4_file_out_res and (len(stdout) > 0):
            vmaf_cmd = 'ffmpeg -i  ' + mp4_file_ref + ' -i ' + mp4_file_out + \
                        ' -lavfi libvmaf="model_path=' + VMAF_2_0_MODEL_FILE + \
                        ':psnr=1:log_path=' + log_file + ':log_fmt=json" -t ' + \
                        duration + ' -f null -'
            ret, stdout, stderr = run_cmd(vmaf_cmd)

            with open(log_file, 'r') as fp:
                vmaf_result = json.load(fp)
                fp.close()

            if vmaf_result:
                if vmaf_result.get('pooled_metrics') is not None and \
                   vmaf_result.get('pooled_metrics').get('vmaf') is not None and \
                   vmaf_result.get('pooled_metrics').get('vmaf').get('mean') is not None:
                    vmaf_score = vmaf_result.get('pooled_metrics').get('vmaf').get('mean')
                return round(float(vmaf_score), 2)
        else:
            input_yuv_file, src_res = self.get_source_info(mp4_file_ref)
            output_yuv_file = '/'.join([self.temp_dir, 'output_temp.yuv'])
            ffmpeg_cmd = 'ffmpeg -y -i ' + mp4_file_out + \
                         ' -pix_fmt yuv420p -s ' + src_res + \
                         ' ' + output_yuv_file
            run_cmd(ffmpeg_cmd)

            sizes = src_res.split('x')
            vmaf_cmd = 'run_vmaf yuv420p ' + sizes[0] + ' ' + sizes[1] +\
                       ' ' + input_yuv_file + ' ' + output_yuv_file +\
                       ' --out-fmt json'
            ret, stdout, stderr = run_cmd(vmaf_cmd)
            # remove the output yuv file
            os.system('rm ' + output_yuv_file)
            vmaf_result = json.loads(stdout)

            # save vmaf results to a json file
            with open(log_file, 'w') as fp:
                json.dump(vmaf_result, fp, indent=4)
                fp.close()

            if vmaf_result:
                if vmaf_result.get('aggregate') is not None and \
                   vmaf_result.get('aggregate').get('VMAF_score') is not None:
                    vmaf_score = vmaf_result['aggregate']['VMAF_score']
                    return round(float(vmaf_score), 2)
        return None

    def get_vmaf_score_yuv(self, ref_yuv, ref_format, ref_res,
                           mp4_file_out, duration):
        output_yuv_file = '/'.join([self.temp_dir, 'output_temp.yuv'])
        ffmpeg_cmd = 'ffmpeg -y -i ' + mp4_file_out + \
                     ' -pix_fmt ' + ref_format + ' -s ' + ref_res + \
                     ' ' + output_yuv_file
        run_cmd(ffmpeg_cmd)

        sizes = ref_res.split('x')
        ret, stdout, stderr = run_cmd("ffmpeg -filters | grep libvmaf")
        log_file = str(Path(mp4_file_out).with_suffix(''))+'_vmaf.json'
        if (len(stdout) > 0):
            vmaf_cmd = 'ffmpeg -f rawvideo -s ' + ref_res + ' -pix_fmt ' +\
                       ref_format + ' -i  ' + ref_yuv +\
                       ' -f rawvideo -s ' + ref_res + ' -pix_fmt ' +\
                       ref_format + ' -i ' + output_yuv_file +\
                        ' -lavfi libvmaf="model_path=' + VMAF_2_0_MODEL_FILE + \
                        ':psnr=1:log_path=' + log_file + ':log_fmt=json" -t ' + \
                        duration + ' -f null -'
            ret, stdout, stderr = run_cmd(vmaf_cmd)
            os.system('rm ' + output_yuv_file)

            with open(log_file, 'r') as fp:
                vmaf_result = json.load(fp)
                fp.close()

            if vmaf_result:
                if vmaf_result.get('pooled_metrics') is not None and \
                   vmaf_result.get('pooled_metrics').get('vmaf') is not None and \
                   vmaf_result.get('pooled_metrics').get('vmaf').get('mean') is not None:
                    vmaf_score = vmaf_result.get('pooled_metrics').get('vmaf').get('mean')
                return round(float(vmaf_score), 2)
        else:
            vmaf_cmd = 'run_vmaf yuv420p ' + sizes[0] + ' ' + sizes[1] +\
                       ' ' + ref_yuv + ' ' + output_yuv_file +\
                       ' --out-fmt json'
            ret, stdout, stderr = run_cmd(vmaf_cmd)
            # remove the output yuv file
            os.system('rm ' + output_yuv_file)
            vmaf_result = json.loads(stdout)

            # save vmaf results to a json file
            with open(log_file, 'w') as fp:
                json.dump(vmaf_result, fp, indent=4)
                fp.close()

            if vmaf_result:
                if vmaf_result.get('aggregate') is not None and \
                   vmaf_result.get('aggregate').get('VMAF_score') is not None:
                    vmaf_score = vmaf_result['aggregate']['VMAF_score']
                    return round(float(vmaf_score), 2)
        return None


class JobInfo:
    def __init__(self, desc, in_file, output_dir, codec, res, mode,
                 i_interval, br, duration, use_surface_enc,
                 input_format, input_res, i_frame_size, temporal_layer_count,
                 enc_loop):
        print('in_file:' + in_file, 'codec:'+codec, 'enc_resolution:'+res,
              'rc_mode:'+mode, 'i_interval:'+i_interval, 'bitrate:'+br,
              'duration:'+duration, 'i_frame_size:'+i_frame_size, sep=',')
        self.desc = desc
        self.input_file = in_file
        self.output_dir = output_dir
        self.output_file = None
        self.codec = codec  # codec to test - 'h265,vp9'
        self.src_res = res
        self.enc_res = res  # encoding resolution of - [width]x[height]
        self.mode = mode  # vbr or cbr - 'cbr, vbr'
        self.bitrate_target = br  # bitrates in kbps
        self.i_interval = i_interval  # key frame interval in seconds
        self.dynamic = None  # Used to create dynamic changes
        self.hierplayer = None
        self.duration = duration  # test duration in secs
        self.device_workdir = '/sdcard/'  # raw file to be used
        self.use_surface_enc = use_surface_enc
        self.input_file_on_device = 'ref.mp4' if use_surface_enc else 'ref.yuv'
        self.input_format = input_format
        self.input_res = input_res
        self.temporal_layer_count = temporal_layer_count
        if 'qcom' in codec:
            self.pix_fmt = 'nv12'
        else:
            # nv12 for hw encoder yuv420p for sw encoder
            self.pix_fmt = 'yuv420p'
        self.i_frame_size = i_frame_size
        self.enc_loop = 0 if enc_loop is None else enc_loop
        #surface encoding without decoding is not possible at the moment
        if not (self.input_format == 'mp4' or self.input_format == 'webm') and self.use_surface_enc:
                print("ERROR: Surface encoding with raw input is no possible")
                exit(0)
        elif (self.input_format == 'mp4' or self.input_format == 'webm') and not self.use_surface_enc:
                print("ERROR: Surface encoding required with encoded input")
                exit(0)

class EncodeJobs:
    def __init__(self, device_model):
        self.job_info_groups = []
        self.prev_file = None
        self.prev_enc_res = None
        self.prev_pix_fmt = None
        self.rd_results = {}
        self.workdir = ''

    def add_job_info_group(self, job_info_group):
        self.job_info_groups.append(job_info_group)

    def run_encodes(self, serial_no):
        for group in self.job_info_groups:
            for job_info in group:
                self.run_one_encode(job_info, serial_no)

        # clear this before running vmaf
        self.in_file_dict = None

    # run one encode job
    def run_one_encode(self, job_info, serial_no):
        # push input file to the device
        if job_info.use_surface_enc:
            if job_info.input_file != self.prev_file:
                adb_cmd = 'adb -s ' + serial_no + ' push ' + \
                            job_info.input_file + ' ' \
                            + job_info.device_workdir\
                            + job_info.input_file_on_device
                run_cmd(adb_cmd)
            self.prev_file = job_info.input_file
        else:
            if (job_info.input_file != self.prev_file or
                    job_info.enc_res != self.prev_enc_res or
                    job_info.pix_fmt != self.prev_pix_fmt):
                input_file = job_info.input_file
                if (job_info.input_format != job_info.pix_fmt or
                    job_info.input_res != job_info.enc_res):

                    input_file = self.workdir + '/' + 'ref.yuv'
                    if job_info.input_format == 'mp4':
                        ffmpeg_cmd = 'ffmpeg -y -i ' + job_info.input_file + \
                                    ' -s ' + job_info.enc_res + \
                                    ' -t ' + job_info.duration + \
                                    ' -pix_fmt ' + job_info.pix_fmt + ' '\
                                    + input_file
                    else:
                        ffmpeg_cmd = 'ffmpeg -y' + ' -s ' + job_info.input_res + \
                             ' -pix_fmt ' + job_info.input_format + \
                             ' -i ' + job_info.input_file + \
                             ' -s ' + job_info.enc_res + \
                             ' -t ' + job_info.duration + \
                             ' -pix_fmt ' + job_info.pix_fmt + ' '\
                             + input_file
                    run_cmd(ffmpeg_cmd)
                adb_cmd = 'adb -s ' + serial_no + ' push ' + input_file +\
                          ' ' + job_info.device_workdir \
                          + job_info.input_file_on_device
                run_cmd(adb_cmd)
            self.prev_file = job_info.input_file
            self.prev_enc_res = job_info.enc_res
            self.prev_pix_fmt = job_info.pix_fmt

        adb_cmd = 'adb -s ' + serial_no + ' shell am instrument -w -r'\
                  + ' -e key ' + job_info.i_interval\
                  + ' -e enc ' + job_info.codec\
                  + ' -e file ' + job_info.device_workdir \
                  + job_info.input_file_on_device\
                  + ' -e test_timeout 20'\
                  + ' -e video_timeout ' + job_info.duration\
                  + ' -e res ' + job_info.enc_res\
                  + ' -e ref_res ' + job_info.enc_res\
                  + ' -e bit ' + job_info.bitrate_target\
                  + ' -e mod ' + job_info.mode\
                  + ' -e fps 30'\
                  + ' -e ifsize ' + job_info.i_frame_size\
                  + ' -e tlc ' + job_info.temporal_layer_count\
                  + ' -e skfr false -e debug false -e ltrc 1'

        if job_info.enc_loop > 0:
            adb_cmd += ' -e loop ' + str(job_info.enc_loop) +\
                        ' -e write false'

        if job_info.dynamic is not None:
            adb_cmd += ' -e dyn ' + job_info.dynamic

        if job_info.hierplayer is not None:
            adb_cmd += ' -e hierl ' + job_info.hierplayer

        adb_cmd += ' -e class ' + TEST_CLASS_NAME + ' ' + JUNIT_RUNNER_NAME
        run_cmd(adb_cmd)

        # find the output file name and pull the output file
        adb_cmd = 'adb -s ' + serial_no + ' shell ls /sdcard/'
        ret, stdout, stderr = run_cmd(adb_cmd)
        output_files = re.findall(ENCAPP_OUTPUT_FILE_NAME_RE,
                                  stdout, re.MULTILINE)

        for file in output_files:
            if file == '':
                continue
            # pull the output file
            adb_cmd = 'adb -s ' + serial_no + ' pull /sdcard/' + file + \
                      ' ' + job_info.output_dir
            run_cmd(adb_cmd)

            # remove the json file on the device too
            adb_cmd = 'adb -s ' + serial_no + ' shell rm /sdcard/' + file
            run_cmd(adb_cmd)

            with open(job_info.output_dir + '/' + file) as json_file:
                data = json.load(json_file)
                job_info.data = data

            # get the media file as well
            fid = job_info.data['id']
            settings = job_info.data['settings']
            print("fid {:s} - br: {:d}, mean br: {:d}, res: {:d}x{:d}, codec: {:s}".format(
                fid, settings['bitrate'], settings['meanbitrate'], settings['width'],
                settings['height'],  settings['codec']))
            encoded_file = job_info.data['encodedfile']
            if len(encoded_file) > 0:
                adb_cmd = 'adb -s ' + serial_no + ' pull /sdcard/' + encoded_file  + \
                          ' ' + job_info.output_dir
                run_cmd(adb_cmd)
                # remove the output
                adb_cmd = 'adb -s ' + serial_no + ' shell rm /sdcard/' + encoded_file
                run_cmd(adb_cmd)
                job_info.output_file = job_info.output_dir + '/' +  encoded_file
            else:
                job_info.output_file = None

    def get_rate_distortion(self, serial_no):
        video_analyzer = VideoAnalyzer(serial_no)
        group_index = 0
        for group in self.job_info_groups:
            bitrates = []
            vmaf_scores = []
            for job_info in group:
                if job_info.output_file is None or job_info.enc_loop > 0:
                    continue
                # get video bitrate and vmaf score
                bitrate = video_analyzer.get_bitrate(job_info.output_file)
                if (job_info.input_format == 'mp4' or
                        job_info.input_format == 'webm'):
                    score = video_analyzer.get_vmaf_score(
                        job_info.input_file, job_info.output_file,
                        job_info.duration)
                else:
                    score = video_analyzer.get_vmaf_score_yuv(
                        job_info.input_file, job_info.input_format,
                        job_info.input_res, job_info.output_file,
                        job_info.duration)
                bitrates.append(bitrate)
                vmaf_scores.append(score)

            if len(bitrates):
                job_info = group[0]
                desc = job_info.desc if job_info.desc is not None \
                    else 'Test ' + str(group_index)
                group_result = {'description': desc,
                                'bitrates': bitrates,
                                'vmaf_scores': vmaf_scores}
                if self.rd_results.get(job_info.input_file) is None:
                    self.rd_results[job_info.input_file] = []
                self.rd_results[job_info.input_file].append(group_result)
            group_index += 1

        with open(self.workdir+'/'+RD_RESULT_FILE_NAME, 'w') as fp:
            json.dump(self.rd_results, fp, indent=4)
            fp.close()

        video_analyzer.finish()


def build_one_enc_job(workdir, device_model, enc_jobs, in_file, codec,
                      res, mode, i_interval, i_frame_size,
                      duration, group_desc, bitrates,
                      use_surface_enc, input_format,
                      input_res, temporal_layer_count, enc_loop):
    base_file_name = os.path.basename(in_file)
    sub_dir = '_'.join([base_file_name, "files"])

    output_dir = workdir + '/' + sub_dir
    os.system('mkdir -p ' + output_dir)
    job_info_group = []

    desc_array = []
    if group_desc != '':
        desc_array.append(group_desc)
    desc_array.append(device_model)
    desc_array.append(codec)
    desc_array.append(res)
    desc_array.append(mode)
    desc_array.append('iint')
    desc_array.append(str(i_interval))
    desc_array.append(i_frame_size)
    desc_array.append('tlc' + str(temporal_layer_count))
    job_desc = '_'.join(desc_array)
    for br in bitrates:
        job_info = JobInfo(job_desc,
                           in_file,
                           output_dir,
                           codec, res, mode,
                           str(i_interval),
                           str(br),
                           str(duration),
                           use_surface_enc,
                           input_format,
                           input_res,
                           i_frame_size,
                           temporal_layer_count,
                           enc_loop
                           )
        job_info_group.append(job_info)

    enc_jobs.add_job_info_group(job_info_group)


def build_tests(tests_json, device_model, test_desc):
    if tests_json is None:
        raise Exception('Test file is empty')

    # get date and time and format it
    now = datetime.now()
    dt_string = now.strftime('%m-%d-%Y_%H_%M')
    workdir = device_model + '_' + dt_string + '_' + test_desc
    os.system('mkdir -p ' + workdir)

    enc_jobs = EncodeJobs(device_model)
    enc_jobs.workdir = workdir
    # save the config json to workdir
    with open(workdir+'/config.json', 'w') as fp:
        json.dump(tests_json, fp, indent=4)
        fp.close()

    for test in tests_json:
        input_files = test.get(KEY_NAME_INPUT_FILES)
        codecs = test.get(KEY_NAME_CODECS)
        enc_resolutions = test.get(KEY_NAME_ENCODE_RESOLUTIONS)
        rc_modes = test.get(KEY_NAME_RC_MODES)
        bitrates = test.get(KEY_NAME_BITRATES)
        i_intervals = test.get(KEY_NAME_I_INTERVALS)
        duration = str(test.get(KEY_NAME_DURATION))
        group_desc = test.get(KEY_NAME_DESCRIPTION)
        i_frame_sizes = test.get(KEY_NAME_I_FRAME_SIZES)
        enc_loop = test.get(KEY_NAME_ENC_LOOP)
        if i_frame_sizes is None:
            i_frame_sizes = ['default']
        if test.get(KEY_NAME_USE_SURFACE_ENC) == 1:
            use_surface_enc = True
        else:
            use_surface_enc = False
        temporal_layer_counts = test.get(KEY_NAME_TEMPORAL_LAYER_COUNTS)
        if temporal_layer_counts is None:
            temporal_layer_counts = [1]
        input_format = test.get(KEY_NAME_INPUT_FORMAT)
        input_res = test.get(KEY_NAME_INPUT_RESOLUTION)
        for in_file in input_files:
            for codec in codecs:
                for res in enc_resolutions:
                    for mode in rc_modes:
                        for i_interval in i_intervals:
                            for i_frame_size in i_frame_sizes:
                                for tl_count in temporal_layer_counts:
                                    build_one_enc_job(workdir,
                                                      device_model,
                                                      enc_jobs,
                                                      in_file,
                                                      codec,
                                                      res,
                                                      mode,
                                                      i_interval,
                                                      i_frame_size,
                                                      duration,
                                                      group_desc,
                                                      bitrates,
                                                      use_surface_enc,
                                                      input_format,
                                                      input_res,
                                                      str(tl_count),
                                                      enc_loop)

    return enc_jobs


def run_encode_tests(tests_json, device_model, serial_no, test_desc):

    # build tests
    enc_jobs = build_tests(tests_json, device_model, test_desc)

    # run encode jobs
    enc_jobs.run_encodes(serial_no)

    enc_jobs.get_rate_distortion(serial_no)


def list_codecs(serial_no):
    adb_cmd = 'adb -s ' + serial_no + ' shell am instrument  -w -r ' +\
              '-e list_codecs a -e class ' + TEST_CLASS_NAME + \
              ' ' + JUNIT_RUNNER_NAME
    run_cmd(adb_cmd)


def main(args):
    if args.config is not None:
        if args.config.endswith('.json') is False:
            print('Error: the config file should have .json extension')
        with open(args.config, 'w') as fp:
            json.dump(sample_config_json_data, fp, indent=4)
            fp.close()
    else:
        device_model, serial_no = check_device(args.serial)
        if args.list_codecs is True:
            list_codecs(serial_no)
        else:
            with open(args.test, 'r') as fp:
                tests_json = json.load(fp)
                fp.close()
                run_encode_tests(tests_json, device_model,
                                 serial_no,
                                 args.desc if args.desc is not None else '')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='A Python script to run \
    ENCAPP tests on Android and collect results. \
    The script will create a directory based on device model and date, \
    and save encoded video and rate distortion results in the directory')
    parser.add_argument('--test', help='Test cases in JSON format.')
    parser.add_argument('--serial', help='Android device serial number')
    parser.add_argument('--config', help='Generate a sample config \
                         file in json format')
    parser.add_argument('--list_codecs', action='store_true',
                        help='List codecs the devices support')
    parser.add_argument('--desc', help='Test description')
    args = parser.parse_args()

    if len(sys.argv) == 1:
        parser.print_help()
        sys.exit()

    main(args)
