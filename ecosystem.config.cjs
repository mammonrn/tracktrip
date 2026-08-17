module.exports = {
  apps: [
    {
      name: 'tracktrip-api',
      script: 'src/index.js',
      cwd: __dirname,
      env: {
        NODE_ENV: 'production',
      },
    },
  ],
};
