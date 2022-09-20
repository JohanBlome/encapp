#!/usr/bin/env python3

import unittest
import os
import sys

MODULE_PATH = os.path.dirname(__file__)
ENCAPP_SCRIPTS_ROOT_DIR = os.path.join(MODULE_PATH, os.pardir, os.pardir)
sys.path.append(ENCAPP_SCRIPTS_ROOT_DIR)

import encapp  # noqa: E402

parseBitrateFieldCases = [
    {
        'name': 'single basic',
        'bitrate_string': '100',
        'bitrate_list': [100],
    },
    {
        'name': 'single prefix',
        'bitrate_string': '100kbps',
        'bitrate_list': [100000],
    },
    {
        'name': 'single prefix space',
        'bitrate_string': '100 kbps',
        'bitrate_list': [100000],
    },
    {
        'name': 'list basic',
        'bitrate_string': '100,200',
        'bitrate_list': [100, 200],
    },
    {
        'name': 'list prefix',
        'bitrate_string': '100kbps,200kbps',
        'bitrate_list': [100000, 200000],
    },
    {
        'name': 'list prefix/mixed',
        'bitrate_string': '100,100kbps,200kbps,1Mbps',
        'bitrate_list': [100, 100000, 200000, 1000000],
    },
    {
        'name': 'range basic',
        'bitrate_string': '100-200-50',
        'bitrate_list': [100, 150],
    },
    {
        'name': 'range prefix',
        'bitrate_string': '100kbps-200kbps-50kbps',
        'bitrate_list': [100000, 150000],
    },
    {
        'name': 'mix 1',
        'bitrate_string': '100,100kbps,200kbps,100kbps-1Mbps-50kbps',
        'bitrate_list': [100, 100000, 200000, 100000, 150000, 200000, 250000,
                         300000, 350000, 400000, 450000, 500000, 550000,
                         600000, 650000, 700000, 750000, 800000, 850000,
                         900000, 950000],
    },
]


class EncappTest(unittest.TestCase):

    def testGetDataBasic(self):
        """parse_bitrate_field tests."""
        for test_case in parseBitrateFieldCases:
            print('...running %s' % test_case['name'])
            bitrate_list = encapp.parse_bitrate_field(
                test_case['bitrate_string'])
            msg = 'unittest failed: "%s"' % test_case['name']
            self.assertTrue(test_case['bitrate_list'] == bitrate_list,
                            msg=(f'{msg} {test_case["bitrate_list"]} != '
                                 f'{bitrate_list}'))


if __name__ == '__main__':
    unittest.main()
