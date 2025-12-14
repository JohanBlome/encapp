//
//  Header.h
//  Encapp
//
//  Created by Lars Johan Blome on 10/9/25.
//

#ifndef X264WrapperBridge_h
#define X264WrapperBridge_h
#include <stdint.h>
#ifdef X264
#include "x264.h"


// X264WrapperBridge.h
void x264_swift_log_callback(void *p_unused, int i_level, const char *psz, va_list arg);
// Helper function to set the log callback
void x264_set_log_callback(x264_param_t *params);

//
x264_t *x264_encoder_open( x264_param_t * );
//x264_t *x264_encoder_open_157(x264_param_t *);
void    x264_encoder_close( x264_t * );
int     x264_param_default_preset( x264_param_t *, const char *preset, const char *tune );
int     x264_encoder_encode( x264_t *, x264_nal_t **pp_nal, int *pi_nal, x264_picture_t *pic_in, x264_picture_t *pic_out );
int     x264_encoder_headers( x264_t *, x264_nal_t **pp_nal, int *pi_nal );
void    x264_encoder_intra_refresh( x264_t * );
int     x264_encoder_invalidate_reference( x264_t *, int64_t pts );
int     x264_encoder_delayed_frames( x264_t * );
int     x264_encoder_maximum_delayed_frames( x264_t * );
#endif
#endif /* X264WrapperBridge_h */
