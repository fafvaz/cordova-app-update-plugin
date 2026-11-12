#import <UIKit/UIKit.h>
#import <Cordova/CDVPlugin.h>

NS_ASSUME_NONNULL_BEGIN

@interface CDVUpdatePlugin : CDVPlugin

- (void)update:(CDVInvokedUrlCommand *)command;

@end

NS_ASSUME_NONNULL_END
