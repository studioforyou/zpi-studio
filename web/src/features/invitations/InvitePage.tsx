import { useEffect } from 'react';
import { Box, Text, Title } from '@mantine/core';
import { useColorScheme } from '@mantine/hooks';
import { useSearchParams } from 'react-router';
import { pl } from '@/shared/i18n/pl';

// The page is opened by invited clients and trainers, not by admins, so it follows the
// mobile app's look (and the system colour scheme) instead of the panel's theme.
const palettes = {
  light: {
    background: 'linear-gradient(170deg, #e6f0f0, #bfdddc 55%, #95c8c7)',
    text: '#10272c',
    muted: '#3e5a5f',
    button: 'linear-gradient(90deg, #3e8a8f, #245a66)',
    onButton: '#ffffff',
  },
  dark: {
    background: 'linear-gradient(170deg, #2f7584, #193a43 55%, #0a1d21)',
    text: '#e7f2f2',
    muted: '#a9c7c8',
    button: '#85c5c3',
    onButton: '#0d2328',
  },
};

/** Link base the app registers for invitations (AndroidManifest, Info.plist). */
const appInviteUrl = 'studioforyou://app/invite';

/**
 * Page behind the link in the invitation e-mail. E-mail apps do not open custom-scheme links,
 * so the link leads here and the button hands the token to the mobile app.
 */
export function InvitePage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');
  const colors = palettes[useColorScheme()];
  const texts = pl.invite;

  useEffect(() => {
    document.title = texts.pageTitle;
  }, [texts.pageTitle]);

  return (
    <Box
      mih="100vh"
      p={24}
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: colors.background,
        color: colors.text,
      }}
    >
      <Box component="main" w="100%" maw={400}>
        <Title order={1} fz={28} mb={8} c={colors.text}>
          {token ? texts.title : texts.invalidTitle}
        </Title>
        <Text fz={14} lh={1.5} mb={token ? 36 : 0} c={colors.muted}>
          {token ? texts.intro : texts.invalidText}
        </Text>
        {token && (
          <>
            <Box
              component="a"
              href={`${appInviteUrl}?token=${encodeURIComponent(token)}`}
              h={54}
              fz={16}
              fw={600}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                borderRadius: 27,
                background: colors.button,
                color: colors.onButton,
                textDecoration: 'none',
              }}
            >
              {texts.openInApp}
            </Box>
            <Text fz={14} lh={1.5} mt={28} c={colors.muted}>
              {texts.noApp}
            </Text>
          </>
        )}
      </Box>
    </Box>
  );
}
