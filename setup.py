from setuptools import setup

with open("README.md", "r", encoding="utf8") as fh:
    long_description = fh.read()

setup(
    name='encapp',
    version=1.22,
    description='',
    long_description=long_description,
    url='https://github.com/johanblome/encapp',
    long_description_content_type='text/markdown',
    author='Johan Blome',
    author_email='jblome@meta.com',
    license='BSD 3-Clause License',
    packages=['encapp', 'encapp/encapp_tool', 'encapp/proto'],
    package_data={},
    include_package_data=False,
    install_requires=['argparse-formatter>=1.4',
                    'beautifulsoup4>=4.13.3',
                    'contourpy>=1.3.1',
                    'cycler>=0.12.1',
                    'fonttools>=4.57.0',
                    'google>=3.0.0',
                    'humanfriendly>=10.0',
                    'iniconfig>=2.1.0',
                    'kiwisolver>=1.4.8',
                    'matplotlib>=3.10.1',
                    'numpy>=1.25.2',
                    'packaging>=24.2',
                    'pandas>=2.0.3',
                    'pillow>=11.1.0',
                    'pluggy>=1.5.0',
                    'protobuf>=6.30.2',
                    'pyparsing>=3.2.3',
                    'pytest>=8.3.3',
                    'python-dateutil>=2.9.0.post0',
                    'pytz>=2025.2',
                    'scipy>=1.15.2',
                    'seaborn>=0.11.1',
                    'six>=1.17.0',
                    'soupsieve>=2.6',
                    'typing_extensions>=4.13.1',
                    'tzdata>=2025.2',
                     ],

    classifiers=[
        'Development Status :: 3 - Alpha',
        'Intended Audience :: Science/Research',
        'Operating System :: OS Independent',
        'Programming Language :: Python :: 3.8',
        'Programming Language :: Python :: 3.9',
        'Programming Language :: Python :: 3.10',
        'Programming Language :: Python :: 3.11',
        'Programming Language :: Python :: 3.12',
        'Programming Language :: Python :: 3.13',
    ],

    entry_points={
        'console_scripts': [
            'encapp=encapp.encapp:main',
            'encappq=encapp.encapp_quality:main',
            'encapps=encapp.encapp_stats_to_csv:main',
        ]
    }
)

