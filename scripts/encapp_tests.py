#!/usr/local/bin/ python3
import os
import subprocess
import json
import sys
import argparse
import re
from datetime import datetime
from plot_rd import RDPlot

KEY_NAME_DISCRIPTION = 'description'
KEY_NAME_INPUT_FILES = 'input_files'
KEY_NAME_CODECS = 'codecs'
KEY_NAME_ENCODE_RESOLUTIONS = 'encode_resolutions'
KEY_NAME_RC_MODES = 'rc_modes'
KEY_NAME_BITRATES = 'bitrates'
KEY_NAME_I_INTERVALS = 'i_intervals'
KEY_NAME_DURATION = 'duration'
KEY_NAME_USE_SURFACE_ENC = 'use_surface_enc'

sample_config_json_data = [
    {
        KEY_NAME_DISCRIPTION: 'sample',
        KEY_NAME_INPUT_FILES: [''],
        KEY_NAME_USE_SURFACE_ENC: 1,
        KEY_NAME_CODECS: ['hevc'],
        KEY_NAME_ENCODE_RESOLUTIONS: ['1280x720'],
        KEY_NAME_RC_MODES: ['cbr'],
        KEY_NAME_BITRATES: [500, 1000, 1500, 2000, 2500],
        KEY_NAME_I_INTERVALS: [2],
        KEY_NAME_DURATION: 10
    }
]
TEST_CLASS_NAME = 'com.facebook.encapp.CodecValidationInstrumentedTest'
JUNIT_RUNNER_NAME = \
    'com.facebook.encapp.test/android.support.test.runner.AndroidJUnitRunner'
ENCAPP_OUTPUT_FILE_NAME_RE = r'.*_[0-9]+fps_[0-9]+x[0-9]+_[0-9]+' + \
                             r'bps_iint[0-9]+_m[0-9]+(?:\.mp4$|\.webm)'
RD_RESULT_FILE_NAME = 'rd_results.json'


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
    device_model = match.group('model')

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
    def __init__(self):
        self.temp_dir = 'temp'
        self.source_file_infos = {}
        os.system('mkdir -p ' + self.temp_dir)

    @staticmethod
    def get_bitrate(video_file):

        if video_file is None:
            raise Exception('Video file does not exist')

        if video_file.endswith('.mp4'):
            ffprobe_cmd = 'ffprobe -v error -select_streams v:0 -show_entries \
                          stream=bit_rate -of default=noprint_wrappers=1:nokey=1 '\
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
        ffprobe_cmd = 'ffprobe -v error -select_streams v:0 -show_entries \
                       stream=height,width -of csv=s=x:p=0 ' + mp4_file
        ret, stdout, stderr = run_cmd(ffprobe_cmd)
        if stdout == '':
            raise Exception('Failed to extract video resolution')
        return stdout.strip('\n')

    def get_source_info(self, mp4_file_ref):
        source_info = self.source_file_infos.get(mp4_file_ref)
        if source_info is None:
            input_yuv_file = self.temp_dir+'/' +\
                             mp4_file_ref.strip('.mp4')+'.yuv'
            ffmpeg_cmd = 'ffmpeg -y -i ' + mp4_file_ref + \
                         ' -pix_fmt yuv420p -vsync 0 ' + input_yuv_file
            run_cmd(ffmpeg_cmd)
            res = self.get_resolution(mp4_file_ref)
            source_info = {'input_yuv_file': input_yuv_file,
                           'resolution': res}
            self.source_file_infos[mp4_file_ref] = source_info
        else:
            input_yuv_file = source_info.get('input_yuv_file')
            res = source_info.get('resolution')
        return input_yuv_file, res

    def get_vmaf_score(self, mp4_file_ref, mp4_file_out):
        output_yuv_file = '/'.join([self.temp_dir, 'output_temp.yuv'])
        input_yuv_file, src_res = self.get_source_info(mp4_file_ref)
        ffmpeg_cmd = 'ffmpeg -y -i ' + mp4_file_out + \
                     ' -pix_fmt yuv420p -vsync 0 -s ' + src_res + \
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

        if vmaf_result:
            if vmaf_result.get('aggregate') is not None and \
               vmaf_result.get('aggregate').get('VMAF_score') is not None:
                vmaf_score = vmaf_result['aggregate']['VMAF_score']
                return round(float(vmaf_score), 2)
        return None


class JobInfo:
    def __init__(self, desc, in_file, output_dir, codec, res, mode,
                 i_interval, br, duration, use_surface_enc):
        print('in_file:' + in_file, 'codec:'+codec, 'enc_resolution:'+res,
              'rc_mode:'+mode, 'i_interval:'+i_interval, 'bitrate:'+br,
              'duration:'+duration, sep=',')
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
        self.input_yuv_file = 'ref.yuv'


class EncodeJobs:
    def __init__(self, device_model):
        self.job_info_groups = []
        self.in_file_dict = []
        self.rd_results = []
        self.workdir = ''

    def add_job_info_group(self, job_info_group):
        self.job_info_groups.append(job_info_group)

    def run_encodes(self, serial_no):
        for group in self.job_info_groups:
            for job_info in group:
                self.run_one_encode(job_info, serial_no)

        # clear this before running vmaf
        self.in_file_dict = []

    # run one encode job
    def run_one_encode(self, job_info, serial_no):
        # push input file to the device
        if job_info.input_file not in self.in_file_dict:
            self.in_file_dict.append(job_info.input_file)
            if job_info.use_surface_enc:
                input_file = job_info.input_file
            else:
                input_file = job_info.input_yuv_file
                ffmpeg_cmd = 'ffmpeg -y -i ' + job_info.input_file + \
                             ' -s ' + job_info.enc_res + \
                             ' -pix_fmt nv12 ' + input_file
                run_cmd(ffmpeg_cmd)
            adb_cmd = 'adb -s ' + serial_no + ' push ' + input_file +\
                      ' ' + job_info.device_workdir + input_file
            run_cmd(adb_cmd)
        else:
            if job_info.use_surface_enc:
                input_file = job_info.input_file
            else:
                input_file = job_info.input_yuv_file

        adb_cmd = 'adb -s ' + serial_no + ' shell am instrument -w -r'\
                  + ' -e key ' + job_info.i_interval\
                  + ' -e enc ' + job_info.codec\
                  + ' -e file ' + job_info.device_workdir + input_file\
                  + ' -e test_timeout 20'\
                  + ' -e video_timeout ' + job_info.duration\
                  + ' -e res ' + job_info.enc_res\
                  + ' -e ref_res ' + job_info.enc_res\
                  + ' -e bit ' + job_info.bitrate_target\
                  + ' -e mod ' + job_info.mode\
                  + ' -e fps 30' \
                  + ' -e skfr false -e debug false -e ltrc 1'

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
            # remove the output
            adb_cmd = 'adb -s ' + serial_no + ' shell rm /sdcard/' + file
            run_cmd(adb_cmd)
            job_info.output_file = job_info.output_dir + '/' + file

    def get_rate_distortion(self):
        video_analyzer = VideoAnalyzer()
        group_index = 0
        for group in self.job_info_groups:
            bitrates = []
            vmaf_scores = []
            for job_info in group:
                # get video bitrate and vmaf score
                bitrate = video_analyzer.get_bitrate(job_info.output_file)
                score = video_analyzer.get_vmaf_score(job_info.input_file,
                                                      job_info.output_file)
                bitrates.append(bitrate)
                vmaf_scores.append(score)

            if len(bitrates):
                job_info = group[0]
                desc = job_info.desc if job_info.desc is not None \
                    else 'Test ' + str(group_index)
                group_result = {'description': desc,
                                'bitrates': bitrates,
                                'vmaf_scores': vmaf_scores}
                self.rd_results.append(group_result)
            group_index += 1

        with open(self.workdir+'/'+RD_RESULT_FILE_NAME, 'w') as fp:
            json.dump(self.rd_results, fp, indent=4)
            fp.close()


def build_tests(tests_json, device_model):
    if tests_json is None:
        raise Exception('Test file is empty')

    # get date and time and format it
    now = datetime.now()
    dt_string = now.strftime('%m-%d-%Y_%H:%M')
    workdir = device_model + '_' + dt_string
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
        group_desc = test.get(KEY_NAME_DISCRIPTION)
        if test.get(KEY_NAME_USE_SURFACE_ENC) == 1:
            use_surface_enc = True
        else:
            use_surface_enc = False
        job_info_group = []
        for in_file in input_files:
            for codec in codecs:
                for res in enc_resolutions:
                    for mode in rc_modes:
                        for i_interval in i_intervals:
                            sub_dir = '_'.join([in_file, codec, res,
                                                mode,
                                                str(i_interval)+'s'])
                            output_dir = workdir + '/' + sub_dir
                            os.system('mkdir -p ' + output_dir)
                            job_info_group = []

                            job_desc = group_desc if group_desc != '' \
                                else '_'.join([device_model,
                                               in_file.strip('.mp4'),
                                               codec,
                                               res,
                                               mode,
                                               'iint',
                                               str(i_interval)])
                            for br in bitrates:
                                job_info = JobInfo(job_desc, in_file,
                                                   output_dir,
                                                   codec, res, mode,
                                                   str(i_interval), str(br),
                                                   str(duration),
                                                   use_surface_enc)
                                job_info_group.append(job_info)

                            enc_jobs.add_job_info_group(job_info_group)

    return enc_jobs


def run_encode_tests(tests_json, serial_no):

    device_model, serial_no = check_device(serial_no)

    # build tests
    enc_jobs = build_tests(tests_json, device_model)

    # run encode jobs
    enc_jobs.run_encodes(serial_no)

    enc_jobs.get_rate_distortion()

    rd_plot = RDPlot('Test RD Curve')
    rd_plot.plot_rd_curve(enc_jobs.workdir+'/'+RD_RESULT_FILE_NAME)


def main(args):
    if args.config is not None:
        if args.config.endswith('.json') is False:
            print('Error: the config file should have .json extension')
        with open(args.config, 'w') as fp:
            json.dump(sample_config_json_data, fp, indent=4)
            fp.close()
    else:
        with open(args.test, 'r') as fp:
            tests_json = json.load(fp)
            fp.close()
            run_encode_tests(tests_json, args.serial)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='A Python script to run \
    ENCAPP tests on Android and collect results. \
    The script will create a directory based on device model and date, \
    and save encoded video and rate distortion results in the directory')
    parser.add_argument('--test', help='Test cases in JSON format.')
    parser.add_argument('--serial', help='Android device serial number')
    parser.add_argument('--config', help='Generate a sample config \
                         file in json format')
    args = parser.parse_args()

    if len(sys.argv) == 1:
        parser.print_help()
        sys.exit()

    main(args)
