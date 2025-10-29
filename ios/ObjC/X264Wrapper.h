#import <Foundation/Foundation.h>
#import "x264.h"

NS_ASSUME_NONNULL_BEGIN

@interface X264Wrapper : NSObject

/*
// Encoder lifecycle
- (instancetype)initWithParams:(x264_param_t *)params;
- (void)close;

// Encoding
- (int)encodeFrame:(x264_picture_t *)picIn
        picOut:(x264_picture_t *)picOut
              nals:(x264_nal_t *_Nonnull*_Nonnull)ppNal
        nalCount:(int *)piNal;

// Headers
- (int)getHeaders:(x264_nal_t *_Nonnull*_Nonnull)ppNal nalCount:(int *)piNal;

// Intra refresh
- (void)intraRefresh;

// Invalidate reference
- (int)invalidateReference:(int64_t)pts;

// Delayed frames
- (int)delayedFrames;
- (int)maximumDelayedFrames;
*/

@end

NS_ASSUME_NONNULL_END
